package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.whisperonnx.SetupActivity;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Request-scoped transcription coordinator with bounded model and segment waits. */
public final class Whisper implements AutoCloseable {
    public interface WhisperListener {
        default void onWhisperEvent(WhisperEvent event) { }
        default void onResultReceived(long jobId, WhisperResult result) { onResultReceived(result); }
        /** Compatibility hook for callers that do not need the request ID. */
        default void onResultReceived(WhisperResult result) { }
    }

    interface EngineFactory {
        RecognitionEngine create(Context context);
    }

    interface RecognitionEngine extends AutoCloseable {
        void awaitReady(long timeoutMs) throws Exception;
        TranscriptionJobRunner.SegmentRecognition recognize(
                long jobId, int segmentIndex, float[] samples, String language,
                Recognizer.Action action, long timeoutMs,
                TranscriptionJobRunner.CancellationToken cancellation) throws Exception;
        boolean isClosed();
        @Override void close();
    }

    private static final String TAG = "Whisper";
    private static final long MODEL_LOAD_TIMEOUT_MS = 120_000L;
    private static final long SEGMENT_TIMEOUT_MS = 120_000L;
    private static final long JOB_TIMEOUT_BASE_MS = 30_000L;
    private static final List<String> MODEL_FILES = Arrays.asList(
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx");

    private final Context context;
    private final SharedPreferences preferences;
    private final EngineFactory engineFactory;
    private final SegmentPlanner segmentPlanner;
    private final ExecutorService executor;
    private final Object stateLock = new Object();
    private final Object engineLock = new Object();
    private final AtomicLong nextJobId = new AtomicLong(1L);
    private final AtomicBoolean preloadSubmitted = new AtomicBoolean(false);
    private volatile WhisperListener listener;
    private volatile boolean closed;
    private volatile RecognitionEngine engine;
    private ActiveJob activeJob;
    private Recognizer.Action configuredAction = Recognizer.ACTION_TRANSCRIBE;
    private String configuredLanguage = "auto";

    public Whisper(Context context) {
        this(context.getApplicationContext(), OnnxRecognitionEngine::new,
                new SegmentPlanner(), Executors.newSingleThreadExecutor(new WhisperThreadFactory()));
    }

    Whisper(Context context, EngineFactory engineFactory, SegmentPlanner segmentPlanner,
            ExecutorService executor) {
        if (context == null || engineFactory == null || segmentPlanner == null || executor == null) {
            throw new IllegalArgumentException("Whisper dependencies are required");
        }
        this.context = context;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.engineFactory = engineFactory;
        this.segmentPlanner = segmentPlanner;
        this.executor = executor;
    }

    public void setListener(WhisperListener listener) {
        this.listener = listener;
    }

    /** Starts model initialization early. Jobs still gate on explicit readiness. */
    public void loadModel() {
        if (closed || !preloadSubmitted.compareAndSet(false, true)) return;
        emit(WhisperEvent.modelLoading());
        executor.execute(() -> {
            try {
                if (!modelFilesPresent()) {
                    launchSetupActivity();
                    emit(WhisperEvent.error(-1L, WhisperEvent.ErrorCode.MODEL_NOT_FOUND,
                            "Whisper model files are not installed", null));
                    return;
                }
                getOrCreateEngine().awaitReady(MODEL_LOAD_TIMEOUT_MS);
                emit(WhisperEvent.modelReady());
            } catch (TimeoutException timeout) {
                invalidateEngine();
                emit(WhisperEvent.error(-1L, WhisperEvent.ErrorCode.MODEL_LOAD_TIMEOUT,
                        "Whisper model initialization timed out", timeout));
            } catch (Exception failure) {
                invalidateEngine();
                emit(WhisperEvent.error(-1L, WhisperEvent.ErrorCode.MODEL_LOAD_FAILED,
                        "Whisper model initialization failed", failure));
            }
        });
    }

    public void setAction(Recognizer.Action action) {
        synchronized (stateLock) {
            configuredAction = action == null ? Recognizer.ACTION_TRANSCRIBE : action;
        }
    }

    public void setLanguage(String language) {
        synchronized (stateLock) {
            configuredLanguage = language == null || language.isEmpty() ? "auto" : language;
        }
    }

    public long start(RecordingData recording) {
        Recognizer.Action action;
        String language;
        synchronized (stateLock) {
            action = configuredAction;
            language = configuredLanguage;
        }
        return start(recording, action, language);
    }

    public long start(RecordingData recording, Recognizer.Action action, String language) {
        if (recording == null) throw new IllegalArgumentException("recording is required");
        final ActiveJob job;
        synchronized (stateLock) {
            if (closed) {
                emit(WhisperEvent.error(-1L, WhisperEvent.ErrorCode.WHISPER_CLOSED,
                        "Whisper is closed", null));
                return -1L;
            }
            if (activeJob != null) {
                emit(WhisperEvent.error(activeJob.jobId, WhisperEvent.ErrorCode.ENGINE_BUSY,
                        "A transcription job is already running", null));
                return -1L;
            }
            job = new ActiveJob(nextJobId.getAndIncrement(), recording,
                    action == null ? Recognizer.ACTION_TRANSCRIBE : action,
                    language == null || language.isEmpty() ? "auto" : language);
            activeJob = job;
        }
        executor.execute(() -> runJob(job));
        return job.jobId;
    }

    public boolean cancel(long jobId) {
        ActiveJob job;
        synchronized (stateLock) {
            job = activeJob;
            if (job == null || job.jobId != jobId) return false;
            job.cancellation.cancel();
        }
        // The ONNX call itself is not interruptible, so isolate late callbacks in the old engine.
        invalidateEngine();
        return true;
    }

    public void stop() {
        long jobId = getActiveJobId();
        if (jobId > 0) cancel(jobId);
    }

    public boolean isInProgress() {
        synchronized (stateLock) { return activeJob != null; }
    }

    public long getActiveJobId() {
        synchronized (stateLock) { return activeJob == null ? -1L : activeJob.jobId; }
    }

    private void runJob(ActiveJob job) {
        int segmentCount = 0;
        try {
            job.cancellation.throwIfCancelled();
            if (!modelFilesPresent()) {
                launchSetupActivity();
                throw new JobException(WhisperEvent.ErrorCode.MODEL_NOT_FOUND,
                        "Whisper model files are not installed", null);
            }
            RecognitionEngine currentEngine = getOrCreateEngine();
            try {
                currentEngine.awaitReady(MODEL_LOAD_TIMEOUT_MS);
            } catch (TimeoutException timeout) {
                throw new JobException(WhisperEvent.ErrorCode.MODEL_LOAD_TIMEOUT,
                        "Whisper model initialization timed out", timeout);
            } catch (Exception failure) {
                throw new JobException(WhisperEvent.ErrorCode.MODEL_LOAD_FAILED,
                        "Whisper model initialization failed", failure);
            }
            job.cancellation.throwIfCancelled();

            List<SegmentRange> ranges = segmentPlanner.plan(job.recording);
            segmentCount = ranges.size();
            if (segmentCount == 0) {
                throw new JobException(WhisperEvent.ErrorCode.RECOGNITION_FAILED,
                        "Recording contains no samples", null);
            }
            emit(WhisperEvent.processingStarted(job.jobId, segmentCount));
            TranscriptionJobRunner runner = new TranscriptionJobRunner(currentEngine::recognize);
            long overallTimeoutMs = safeOverallTimeout(segmentCount);
            TranscriptionJobRunner.JobRequest request = new TranscriptionJobRunner.JobRequest(
                    job.jobId, job.recording, job.action, job.language, ranges,
                    SEGMENT_TIMEOUT_MS, overallTimeoutMs, false);
            WhisperResult result = runner.run(request, job.cancellation,
                    (index, count) -> emitForActive(job,
                            WhisperEvent.segmentStarted(job.jobId, index, count)));
            job.cancellation.throwIfCancelled();
            String replaced = WordReplacements.apply(preferences, result.getResult());
            WhisperResult finalResult = result.withResult(replaced);
            if (isActive(job)) {
                WhisperListener current = listener;
                if (current != null) current.onResultReceived(job.jobId, finalResult);
                emitForActive(job, WhisperEvent.completed(job.jobId, segmentCount));
            }
        } catch (TranscriptionJobRunner.JobFailure failure) {
            if (failure.getReason() == TranscriptionJobRunner.FailureReason.CANCELLED
                    || job.cancellation.isCancelled()) {
                emitForActive(job, WhisperEvent.cancelled(job.jobId));
            } else {
                WhisperEvent.ErrorCode code;
                switch (failure.getReason()) {
                    case SEGMENT_TIMEOUT: code = WhisperEvent.ErrorCode.SEGMENT_TIMEOUT; break;
                    case JOB_TIMEOUT: code = WhisperEvent.ErrorCode.JOB_TIMEOUT; break;
                    default: code = WhisperEvent.ErrorCode.RECOGNITION_FAILED;
                }
                invalidateEngine();
                emitForActive(job, WhisperEvent.error(job.jobId, code,
                        failure.getMessage(), failure));
            }
        } catch (JobException failure) {
            invalidateEngine();
            emitForActive(job, WhisperEvent.error(job.jobId, failure.code,
                    failure.getMessage(), failure.getCause()));
        } catch (Throwable failure) {
            if (job.cancellation.isCancelled()) {
                emitForActive(job, WhisperEvent.cancelled(job.jobId));
            } else {
                Log.e(TAG, "Unhandled transcription failure", failure);
                invalidateEngine();
                emitForActive(job, WhisperEvent.error(job.jobId,
                        WhisperEvent.ErrorCode.INTERNAL_ERROR,
                        failure.getMessage(), failure));
            }
        } finally {
            synchronized (stateLock) {
                if (activeJob == job) activeJob = null;
            }
        }
    }

    private boolean isActive(ActiveJob job) {
        synchronized (stateLock) { return !closed && activeJob == job; }
    }

    private void emitForActive(ActiveJob job, WhisperEvent event) {
        if (isActive(job)) emit(event);
    }

    private void emit(WhisperEvent event) {
        WhisperListener current = listener;
        if (current != null && !closed) current.onWhisperEvent(event);
    }

    private RecognitionEngine getOrCreateEngine() {
        synchronized (engineLock) {
            if (closed) throw new IllegalStateException("Whisper is closed");
            if (engine == null || engine.isClosed()) engine = engineFactory.create(context);
            return engine;
        }
    }

    private void invalidateEngine() {
        RecognitionEngine previous;
        synchronized (engineLock) {
            previous = engine;
            engine = null;
        }
        if (previous != null) previous.close();
    }

    private boolean modelFilesPresent() {
        File folder = context.getExternalFilesDir(null);
        if (folder == null || !folder.isDirectory()) return false;
        for (String name : MODEL_FILES) {
            File model = new File(folder, name);
            if (!model.isFile() || model.length() == 0L) return false;
        }
        return true;
    }

    private void launchSetupActivity() {
        try {
            Intent intent = new Intent(context, SetupActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to launch model setup", e);
        }
    }

    private static long safeOverallTimeout(int segmentCount) {
        try {
            return Math.addExact(JOB_TIMEOUT_BASE_MS,
                    Math.multiplyExact((long) segmentCount, SEGMENT_TIMEOUT_MS));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public void unloadModel() { close(); }

    @Override
    public void close() {
        ActiveJob job;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            job = activeJob;
            activeJob = null;
            if (job != null) job.cancellation.cancel();
        }
        listener = null;
        invalidateEngine();
        executor.shutdownNow();
    }

    private static final class ActiveJob {
        final long jobId;
        final RecordingData recording;
        final Recognizer.Action action;
        final String language;
        final TranscriptionJobRunner.CancellationToken cancellation =
                new TranscriptionJobRunner.CancellationToken();

        ActiveJob(long jobId, RecordingData recording, Recognizer.Action action, String language) {
            this.jobId = jobId;
            this.recording = recording;
            this.action = action;
            this.language = language;
        }
    }

    private static final class JobException extends Exception {
        final WhisperEvent.ErrorCode code;
        JobException(WhisperEvent.ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private static final class WhisperThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "whisper-job-runner");
            thread.setDaemon(true);
            return thread;
        }
    }

    /** One callback-correlated ONNX recognizer generation. Closed generations are never reused. */
    private static final class OnnxRecognitionEngine implements RecognitionEngine {
        private final Recognizer recognizer;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<TranscriptionJobRunner.SegmentRecognition>>
                pending = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final RecognizerListener recognizerListener;

        OnnxRecognitionEngine(Context context) {
            recognizer = new Recognizer(context, false, new NeuralNetworkApi.InitListener() {
                @Override public void onInitializationFinished() { ready.complete(null); }
                @Override public void onError(int[] reasons, long value) {
                    ready.completeExceptionally(new IllegalStateException(
                            "Recognizer model initialization failed"));
                }
            });
            recognizerListener = new RecognizerListener() {
                @Override
                public void onSpeechRecognizedResult(String text, String languageCode,
                                                     double confidenceScore, boolean isFinal) {
                    if (!isFinal || closed.get()) return;
                    CompletableFuture<TranscriptionJobRunner.SegmentRecognition> future =
                            pending.getAndSet(null);
                    if (future != null) future.complete(
                            new TranscriptionJobRunner.SegmentRecognition(
                                    text, languageCode, confidenceScore));
                }

                @Override
                public void onError(int[] reasons, long value) {
                    CompletableFuture<TranscriptionJobRunner.SegmentRecognition> future =
                            pending.getAndSet(null);
                    if (future != null) future.completeExceptionally(
                            new IllegalStateException("Recognizer execution failed"));
                }
            };
            recognizer.addCallback(recognizerListener);
        }

        @Override
        public void awaitReady(long timeoutMs) throws Exception {
            try {
                ready.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw failure;
            }
        }

        @Override
        public TranscriptionJobRunner.SegmentRecognition recognize(
                long jobId, int segmentIndex, float[] samples, String language,
                Recognizer.Action action, long timeoutMs,
                TranscriptionJobRunner.CancellationToken cancellation) throws Exception {
            if (closed.get()) throw new CancellationException("Recognizer generation closed");
            cancellation.throwIfCancelled();
            CompletableFuture<TranscriptionJobRunner.SegmentRecognition> future =
                    new CompletableFuture<>();
            if (!pending.compareAndSet(null, future)) {
                throw new IllegalStateException("Recognizer already has a pending segment");
            }
            try {
                recognizer.recognize(samples, 1, language, action);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                        Math.max(1L, timeoutMs));
                while (true) {
                    cancellation.throwIfCancelled();
                    if (closed.get()) throw new CancellationException("Recognizer generation closed");
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0L) throw new TimeoutException("Segment timed out");
                    long waitMs = Math.max(1L, Math.min(100L,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    try {
                        return future.get(waitMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException pollingTimeout) {
                        // Poll cancellation and deadline again.
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof Exception) throw (Exception) cause;
                        throw failure;
                    }
                }
            } finally {
                pending.compareAndSet(future, null);
            }
        }

        @Override public boolean isClosed() { return closed.get(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ready.completeExceptionally(new CancellationException("Recognizer closed"));
            CompletableFuture<TranscriptionJobRunner.SegmentRecognition> future =
                    pending.getAndSet(null);
            if (future != null) future.completeExceptionally(
                    new CancellationException("Recognizer closed"));
            recognizer.removeCallback(recognizerListener);
            recognizer.destroy();
        }
    }
}
