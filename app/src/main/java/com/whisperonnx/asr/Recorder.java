package com.whisperonnx.asr;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whisperonnx.R;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object(); // Lock object for wait/notify

    private volatile boolean shouldStartRecording = false;
    private boolean autoStopOnSilence = false;
    private static final int VAD_FRAME_SIZE = 480;
    private SharedPreferences sp;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
        sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }


    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void initVad(){
        autoStopOnSilence = true;
        Log.d(TAG, "Auto-stop on silence enabled");
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish
        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait(); // Wait until notified by the recording thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }


    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        boolean useBluetoothMic = sp.getBoolean("useBluetoothMic", false);
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        configureBluetooth(audioManager, useBluetoothMic, true);

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        // Configurable max recording duration (default 120 seconds)
        int maxRecordingSeconds = sp.getInt("maxRecordingSeconds", 120);
        int maxBytes = sampleRateInHz * bytesPerSample * channels * maxRecordingSeconds;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        // Always create VAD for segment boundary tracking
        int silenceDurationMs = sp.getInt("silenceDurationMs", 800);
        VadWebRTC vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(silenceDurationMs)
                .setSpeechDurationMs(200)
                .build();

        List<Integer> segmentBoundaries = new ArrayList<>();
        boolean speechActive = false;
        boolean recordingStarted = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  //VAD needs 16 bit

        while (mInProgress.get() && totalBytesRead < maxBytes) {
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);
                totalBytesRead += bytesRead;
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            // VAD processing for segment boundary tracking
            byte[] outputBufferByteArray = outputBuffer.toByteArray();
            if (outputBufferByteArray.length >= VAD_FRAME_SIZE * 2) {
                // Always use the last VAD_FRAME_SIZE * 2 bytes (16 bit) from outputBuffer for VAD
                System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_SIZE * 2, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                boolean isSpeech = vad.isSpeech(vadAudioBuffer);

                if (isSpeech) {
                    if (!speechActive) {
                        Log.d(TAG, "VAD Speech detected");
                        if (autoStopOnSilence) {
                            sendUpdate(MSG_RECORDING);
                        }
                    }
                    speechActive = true;
                } else {
                    if (speechActive) {
                        // Speech -> silence transition: mark segment boundary
                        segmentBoundaries.add(totalBytesRead);
                        Log.d(TAG, "Segment boundary at byte offset: " + totalBytesRead);

                        if (autoStopOnSilence) {
                            // Auto-mode: stop recording on silence after speech
                            speechActive = false;
                            mInProgress.set(false);
                        }
                    }
                    speechActive = false;
                }
            }

            // In non-auto mode, send MSG_RECORDING immediately
            if (!autoStopOnSilence && !recordingStarted) {
                sendUpdate(MSG_RECORDING);
                recordingStarted = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);
        Log.d(TAG, "Segment boundaries: " + segmentBoundaries.size());

        vad.close();
        if (autoStopOnSilence) {
            autoStopOnSilence = false;
        }

        audioRecord.stop();
        audioRecord.release();
        configureBluetooth(audioManager, useBluetoothMic, false);

        // Save recorded audio data and segment boundaries to RecordBuffer
        RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
        RecordBuffer.setSegmentBoundaries(segmentBoundaries);

        if (totalBytesRead > 6400){  //min 0.2s
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

        // Notify the waiting thread that recording is complete
        synchronized (fileSavedLock) {
            fileSavedLock.notify(); // Notify that recording is finished
        }

    }

    // Package-private for testing
    void configureBluetooth(AudioManager audioManager, boolean useBluetoothMic, boolean start) {
        if (useBluetoothMic) {
            if (start) {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            } else {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        }
    }

}
