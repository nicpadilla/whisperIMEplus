package com.whisperonnx.asr;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one capture request at a time. Stop/cancel are non-blocking and every accepted request
 * produces exactly one typed terminal event.
 */
public final class Recorder implements AutoCloseable {
    public interface RecorderListener {
        void onRecorderEvent(RecorderEvent event);
    }

    interface CaptureBackend extends AutoCloseable {
        RecordingData capture(CaptureRequest request, CaptureControl control,
                              CaptureObserver observer) throws CaptureFailure;
        @Override default void close() { }
    }

    interface CaptureObserver {
        void onSpeechStarted();
        void onRouteChanged(String requestedRoute, String actualRoute);
    }

    static final class CaptureRequest {
        final long requestId;
        final boolean autoStopOnSilence;

        CaptureRequest(long requestId, boolean autoStopOnSilence) {
            this.requestId = requestId;
            this.autoStopOnSilence = autoStopOnSilence;
        }
    }

    static final class CaptureControl {
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        void requestStop() { stopRequested.set(true); }
        void cancel() { cancelled.set(true); stopRequested.set(true); }
        boolean shouldStop() { return stopRequested.get(); }
        boolean isCancelled() { return cancelled.get(); }
    }

    static final class CaptureFailure extends Exception {
        final RecorderEvent.ErrorCode code;

        CaptureFailure(RecorderEvent.ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        CaptureFailure(RecorderEvent.ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private static final String TAG = "Recorder";
    static final int SAMPLE_RATE_HZ = 16000;
    static final int CHANNEL_COUNT = 1;
    static final int BYTES_PER_SAMPLE = 2;
    static final int VAD_FRAME_SAMPLES = 480;
    static final int VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * BYTES_PER_SAMPLE * CHANNEL_COUNT;
    static final int MIN_VALID_AUDIO_BYTES = 6400;

    private final Object stateLock = new Object();
    private final AtomicLong nextRequestId = new AtomicLong(1L);
    private final CaptureBackend captureBackend;
    private final ExecutorService executor;
    private volatile RecorderListener listener;
    private volatile boolean closed;
    private ActiveRequest activeRequest;

    public Recorder(Context context) {
        this(new AndroidCaptureBackend(context.getApplicationContext()),
                Executors.newSingleThreadExecutor(new RecorderThreadFactory()));
    }

    Recorder(CaptureBackend captureBackend, ExecutorService executor) {
        if (captureBackend == null || executor == null) {
            throw new IllegalArgumentException("captureBackend and executor are required");
        }
        this.captureBackend = captureBackend;
        this.executor = executor;
    }

    public void setListener(RecorderListener listener) {
        this.listener = listener;
    }

    public long start() {
        return start(false);
    }

    public long start(boolean autoStopOnSilence) {
        final ActiveRequest request;
        synchronized (stateLock) {
            if (closed || activeRequest != null) return -1L;
            request = new ActiveRequest(nextRequestId.getAndIncrement(), autoStopOnSilence);
            activeRequest = request;
        }
        executor.execute(() -> runCapture(request));
        return request.requestId;
    }

    /** Requests normal completion; captured audio remains eligible for transcription. */
    public boolean requestStop(long requestId) {
        synchronized (stateLock) {
            if (activeRequest == null || activeRequest.requestId != requestId) return false;
            activeRequest.control.requestStop();
            return true;
        }
    }

    /** Cancels and discards the active capture. */
    public boolean cancel(long requestId) {
        synchronized (stateLock) {
            if (activeRequest == null || activeRequest.requestId != requestId) return false;
            activeRequest.control.cancel();
            return true;
        }
    }

    public boolean isInProgress() {
        synchronized (stateLock) {
            return activeRequest != null;
        }
    }

    public long getActiveRequestId() {
        synchronized (stateLock) {
            return activeRequest == null ? -1L : activeRequest.requestId;
        }
    }

    private void runCapture(ActiveRequest request) {
        emit(RecorderEvent.captureStarted(request.requestId));
        try {
            RecordingData recording = captureBackend.capture(
                    new CaptureRequest(request.requestId, request.autoStopOnSilence),
                    request.control,
                    new CaptureObserver() {
                        @Override public void onSpeechStarted() {
                            emitNonTerminal(request, RecorderEvent.speechStarted(request.requestId));
                        }

                        @Override public void onRouteChanged(String requestedRoute,
                                                             String actualRoute) {
                            emitNonTerminal(request, RecorderEvent.routeChanged(
                                    request.requestId, requestedRoute, actualRoute));
                        }
                    });
            if (request.control.isCancelled()) {
                finish(request, RecorderEvent.cancelled(request.requestId));
            } else if (recording == null || recording.getValidByteCount() < MIN_VALID_AUDIO_BYTES) {
                finish(request, RecorderEvent.error(request.requestId,
                        RecorderEvent.ErrorCode.NO_AUDIO, "No usable voice input was captured", null));
            } else {
                finish(request, RecorderEvent.completed(request.requestId, recording));
            }
        } catch (CaptureFailure failure) {
            RecorderEvent terminal = request.control.isCancelled()
                    ? RecorderEvent.cancelled(request.requestId)
                    : RecorderEvent.error(request.requestId, failure.code,
                    failure.getMessage(), failure.getCause());
            finish(request, terminal);
        } catch (Throwable failure) {
            Log.e(TAG, "Unhandled recording failure", failure);
            RecorderEvent terminal = request.control.isCancelled()
                    ? RecorderEvent.cancelled(request.requestId)
                    : RecorderEvent.error(request.requestId,
                    RecorderEvent.ErrorCode.INTERNAL_ERROR,
                    failure.getMessage(), failure);
            finish(request, terminal);
        }
    }

    private void emitNonTerminal(ActiveRequest request, RecorderEvent event) {
        synchronized (stateLock) {
            if (closed || activeRequest != request || request.terminalDelivered.get()) return;
        }
        emit(event);
    }

    private void finish(ActiveRequest request, RecorderEvent terminal) {
        if (!request.terminalDelivered.compareAndSet(false, true)) return;
        synchronized (stateLock) {
            if (activeRequest == request) activeRequest = null;
        }
        emit(terminal);
    }

    private void emit(RecorderEvent event) {
        if (event != null && event.getType() == RecorderEvent.Type.ROUTE_CHANGED) {
            Log.i(TAG, "Audio route request=" + event.getRequestedRoute()
                    + ", actual=" + event.getActualRoute());
        }
        RecorderListener current = listener;
        if (current != null && !closed) current.onRecorderEvent(event);
    }

    @Override
    public void close() {
        ActiveRequest request;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            request = activeRequest;
            if (request != null) request.control.cancel();
            activeRequest = null;
        }
        listener = null;
        executor.shutdownNow();
        try {
            captureBackend.close();
        } catch (Exception e) {
            Log.w(TAG, "Recorder backend cleanup failed", e);
        }
    }

    private static final class ActiveRequest {
        final long requestId;
        final boolean autoStopOnSilence;
        final CaptureControl control = new CaptureControl();
        final AtomicBoolean terminalDelivered = new AtomicBoolean(false);

        ActiveRequest(long requestId, boolean autoStopOnSilence) {
            this.requestId = requestId;
            this.autoStopOnSilence = autoStopOnSilence;
        }
    }

    private static final class RecorderThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "whisper-recorder");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class AndroidCaptureBackend implements CaptureBackend {
        private final Context context;
        private final SharedPreferences preferences;
        private final BluetoothAudioRouter audioRouter;

        AndroidCaptureBackend(Context context) {
            this.context = context;
            this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
            this.audioRouter = new BluetoothAudioRouter(context);
        }

        @Override
        public RecordingData capture(CaptureRequest request, CaptureControl control,
                                     CaptureObserver observer) throws CaptureFailure {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new CaptureFailure(RecorderEvent.ErrorCode.PERMISSION_DENIED,
                        "RECORD_AUDIO permission is not granted");
            }

            final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
            int platformBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ, channelConfig, audioEncoding);
            if (platformBufferSize <= 0) platformBufferSize = VAD_FRAME_BYTES * 4;
            platformBufferSize = Math.max(platformBufferSize, VAD_FRAME_BYTES);

            boolean bluetoothRequested = preferences.getBoolean("bluetooth", false);
            AudioRecord audioRecord = null;
            VadWebRTC vad = null;
            BluetoothAudioRouter.RouteSession routeSession = null;
            long captureStartMs = SystemClock.elapsedRealtime();
            try {
                if (control.shouldStop()) return null;
                routeSession = audioRouter.open(bluetoothRequested,
                        BluetoothAudioRouter.DEFAULT_ROUTE_TIMEOUT_MS, control::shouldStop);
                observer.onRouteChanged(routeSession.getRequestedRoute(),
                        routeSession.getActualRoute());
                if (control.isCancelled()) return null;

                try {
                    audioRecord = new AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setChannelMask(channelConfig)
                                    .setEncoding(audioEncoding)
                                    .setSampleRate(SAMPLE_RATE_HZ)
                                    .build())
                            .setBufferSizeInBytes(platformBufferSize)
                            .build();
                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("AudioRecord did not initialize");
                    }
                    audioRecord.startRecording();
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        throw new IllegalStateException("AudioRecord did not start");
                    }
                } catch (RuntimeException e) {
                    throw new CaptureFailure(RecorderEvent.ErrorCode.INITIALIZATION_FAILED,
                            "Unable to initialize microphone capture", e);
                }

                int silenceDurationMs = preferences.getInt("silenceDurationMs", 800);
                final VadWebRTC activeVad = com.konovalov.vad.webrtc.Vad.builder()
                        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                        .setFrameSize(FrameSize.FRAME_SIZE_480)
                        .setMode(Mode.VERY_AGGRESSIVE)
                        .setSilenceDurationMs(silenceDurationMs)
                        .setSpeechDurationMs(200)
                        .build();
                vad = activeVad;

                int maxSeconds = Math.max(1, preferences.getInt("maxRecordingSeconds", 120));
                int maxBytes = Math.multiplyExact(
                        Math.multiplyExact(SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * CHANNEL_COUNT,
                                maxSeconds), 1);
                OwnedByteArrayOutputStream output = new OwnedByteArrayOutputStream(
                        Math.min(maxBytes, SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * 10));
                byte[] readBuffer = new byte[Math.max(VAD_FRAME_BYTES, platformBufferSize)];
                VadFrameAssembler assembler = new VadFrameAssembler(VAD_FRAME_BYTES);
                List<Integer> boundaries = new ArrayList<>();
                final boolean[] speechActive = {false};
                final boolean[] speechEventSent = {false};
                final boolean[] autoSilenceReached = {false};
                int zeroReads = 0;

                while (!control.shouldStop() && output.size() < maxBytes) {
                    if (routeSession.refresh()) {
                        observer.onRouteChanged(routeSession.getRequestedRoute(),
                                routeSession.getActualRoute());
                    }
                    int bytesRequested = Math.min(readBuffer.length, maxBytes - output.size());
                    int bytesRead = audioRecord.read(readBuffer, 0, bytesRequested);
                    if (bytesRead > 0) {
                        zeroReads = 0;
                        output.write(readBuffer, 0, bytesRead);
                        assembler.accept(readBuffer, 0, bytesRead, (frame, endByteOffset) -> {
                            boolean speech = activeVad.isSpeech(frame);
                            if (speech) {
                                if (!speechEventSent[0]) {
                                    speechEventSent[0] = true;
                                    observer.onSpeechStarted();
                                }
                                speechActive[0] = true;
                            } else if (speechActive[0]) {
                                int boundary = (int) Math.min(Integer.MAX_VALUE, endByteOffset);
                                boundaries.add(boundary);
                                speechActive[0] = false;
                                if (request.autoStopOnSilence) autoSilenceReached[0] = true;
                            }
                        });
                        if (autoSilenceReached[0]) break;
                    } else if (bytesRead == 0) {
                        if (++zeroReads >= 20) {
                            throw new CaptureFailure(RecorderEvent.ErrorCode.READ_FAILED,
                                    "Microphone returned no data repeatedly");
                        }
                    } else {
                        throw new CaptureFailure(RecorderEvent.ErrorCode.READ_FAILED,
                                "AudioRecord.read failed with code " + bytesRead);
                    }
                }

                if (control.isCancelled()) return null;
                long durationMs = SystemClock.elapsedRealtime() - captureStartMs;
                routeSession.refresh();
                return RecordingData.takeOwnership(output.ownedBuffer(), output.size(),
                        SAMPLE_RATE_HZ, CHANNEL_COUNT, BYTES_PER_SAMPLE, audioEncoding,
                        boundaries, durationMs, routeSession.getRequestedRoute(),
                        routeSession.getActualRoute());
            } finally {
                if (vad != null) {
                    try { vad.close(); } catch (RuntimeException e) {
                        Log.w(TAG, "VAD cleanup failed", e);
                    }
                }
                if (audioRecord != null) {
                    try {
                        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "AudioRecord stop failed", e);
                    }
                    try { audioRecord.release(); } catch (RuntimeException e) {
                        Log.w(TAG, "AudioRecord release failed", e);
                    }
                }
                if (routeSession != null) routeSession.close();
            }
        }

        @Override public void close() { audioRouter.close(); }
    }

    /** Exposes the owned backing array only at the one-time RecordingData handoff. */
    private static final class OwnedByteArrayOutputStream extends ByteArrayOutputStream {
        OwnedByteArrayOutputStream(int size) { super(Math.max(32, size)); }
        byte[] ownedBuffer() { return buf; }
    }
}
