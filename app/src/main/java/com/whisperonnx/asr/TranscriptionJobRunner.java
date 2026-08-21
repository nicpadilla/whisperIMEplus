package com.whisperonnx.asr;

import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure sequential execution and result aggregation for one transcription job. */
public final class TranscriptionJobRunner {
    public interface NanoClock {
        long nanoTime();
    }

    public interface RecognitionClient {
        SegmentRecognition recognize(long jobId, int segmentIndex, float[] samples,
                                     String language, Recognizer.Action action,
                                     long timeoutMs, CancellationToken cancellation)
                throws Exception;
    }

    public interface ProgressListener {
        void onSegmentStarted(int segmentIndex, int segmentCount);
    }

    public enum FailureReason {
        CANCELLED,
        SEGMENT_TIMEOUT,
        JOB_TIMEOUT,
        RECOGNITION_FAILED,
        NO_SEGMENTS
    }

    public static final class JobFailure extends Exception {
        private final FailureReason reason;

        JobFailure(FailureReason reason, String message) { super(message); this.reason = reason; }
        JobFailure(FailureReason reason, String message, Throwable cause) {
            super(message, cause); this.reason = reason;
        }
        public FailureReason getReason() { return reason; }
    }

    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
        public void throwIfCancelled() throws JobFailure {
            if (isCancelled()) throw new JobFailure(FailureReason.CANCELLED, "Job cancelled");
        }
    }

    public static final class SegmentRecognition {
        public final String text;
        public final String language;
        public final double confidence;

        public SegmentRecognition(String text, String language, double confidence) {
            this.text = text == null ? "" : text;
            this.language = language == null ? "??" : language;
            this.confidence = confidence;
        }
    }

    public static final class JobRequest {
        final long jobId;
        final RecordingData recording;
        final Recognizer.Action action;
        final String language;
        final List<SegmentRange> ranges;
        final long perSegmentTimeoutMs;
        final long overallTimeoutMs;
        final boolean overlapAwareJoin;

        public JobRequest(long jobId, RecordingData recording, Recognizer.Action action,
                          String language, List<SegmentRange> ranges,
                          long perSegmentTimeoutMs, long overallTimeoutMs,
                          boolean overlapAwareJoin) {
            if (jobId <= 0 || recording == null || action == null || ranges == null) {
                throw new IllegalArgumentException("Invalid transcription job request");
            }
            this.jobId = jobId;
            this.recording = recording;
            this.action = action;
            this.language = language == null || language.isEmpty() ? "auto" : language;
            this.ranges = ranges;
            this.perSegmentTimeoutMs = perSegmentTimeoutMs;
            this.overallTimeoutMs = overallTimeoutMs;
            this.overlapAwareJoin = overlapAwareJoin;
        }
    }

    private final RecognitionClient recognitionClient;
    private final NanoClock clock;

    public TranscriptionJobRunner(RecognitionClient recognitionClient) {
        this(recognitionClient, System::nanoTime);
    }

    TranscriptionJobRunner(RecognitionClient recognitionClient, NanoClock clock) {
        if (recognitionClient == null || clock == null) {
            throw new IllegalArgumentException("recognitionClient and clock are required");
        }
        this.recognitionClient = recognitionClient;
        this.clock = clock;
    }

    public WhisperResult run(JobRequest request, CancellationToken cancellation,
                             ProgressListener progress) throws JobFailure {
        if (request.ranges.isEmpty()) {
            throw new JobFailure(FailureReason.NO_SEGMENTS, "Recording produced no segments");
        }
        cancellation.throwIfCancelled();
        final long started = clock.nanoTime();
        final long overallDeadline = addMillis(started, request.overallTimeoutMs);
        List<WhisperSegmentResult> segmentResults = new ArrayList<>();
        List<String> textSegments = new ArrayList<>();
        String recognitionLanguage = request.language;

        for (int index = 0; index < request.ranges.size(); index++) {
            cancellation.throwIfCancelled();
            if (clock.nanoTime() > overallDeadline) {
                throw new JobFailure(FailureReason.JOB_TIMEOUT, "Transcription job timed out");
            }
            if (progress != null) progress.onSegmentStarted(index, request.ranges.size());
            SegmentRange range = request.ranges.get(index);
            float[] samples = AudioSampleConverter.toFloatSamples(request.recording, range);
            long remainingMs = Math.max(1L, nanosToMillis(overallDeadline - clock.nanoTime()));
            long segmentTimeoutMs = Math.max(1L,
                    Math.min(request.perSegmentTimeoutMs, remainingMs));
            SegmentRecognition recognized;
            try {
                recognized = recognitionClient.recognize(request.jobId, index, samples,
                        recognitionLanguage, request.action, segmentTimeoutMs, cancellation);
            } catch (TimeoutException timeout) {
                throw new JobFailure(FailureReason.SEGMENT_TIMEOUT,
                        "Segment " + (index + 1) + " timed out", timeout);
            } catch (JobFailure failure) {
                throw failure;
            } catch (Exception failure) {
                if (cancellation.isCancelled()) {
                    throw new JobFailure(FailureReason.CANCELLED, "Job cancelled", failure);
                }
                throw new JobFailure(FailureReason.RECOGNITION_FAILED,
                        "Segment " + (index + 1) + " failed", failure);
            }
            cancellation.throwIfCancelled();
            WhisperSegmentResult result = new WhisperSegmentResult(index, range,
                    recognized.text, recognized.language, recognized.confidence);
            segmentResults.add(result);
            textSegments.add(recognized.text);
            if ("auto".equals(recognitionLanguage) && isUsableLanguage(recognized.language)) {
                recognitionLanguage = recognized.language;
            }
        }

        String finalLanguage = "auto".equals(request.language)
                ? aggregateLanguage(segmentResults) : request.language;
        double finalConfidence = aggregateConfidence(segmentResults);
        String joined = TranscriptJoiner.join(textSegments, request.overlapAwareJoin);
        return new WhisperResult(joined, finalLanguage, request.action,
                finalConfidence, segmentResults);
    }

    private static boolean isUsableLanguage(String language) {
        return language != null && !language.isEmpty() && !"auto".equals(language)
                && !"??".equals(language);
    }

    private static String aggregateLanguage(List<WhisperSegmentResult> results) {
        Map<String, Long> weights = new HashMap<>();
        for (WhisperSegmentResult result : results) {
            if (!isUsableLanguage(result.getLanguage())) continue;
            long weight = result.getRange().lengthSamples();
            weights.put(result.getLanguage(), weights.getOrDefault(result.getLanguage(), 0L) + weight);
        }
        String selected = "??";
        long best = -1L;
        for (Map.Entry<String, Long> entry : weights.entrySet()) {
            if (entry.getValue() > best
                    || (entry.getValue() == best && entry.getKey().compareTo(selected) < 0)) {
                selected = entry.getKey();
                best = entry.getValue();
            }
        }
        return selected;
    }

    private static double aggregateConfidence(List<WhisperSegmentResult> results) {
        double weighted = 0.0;
        long weight = 0L;
        for (WhisperSegmentResult result : results) {
            if (!Double.isFinite(result.getConfidence())) continue;
            int segmentWeight = result.getRange().lengthSamples();
            weighted += result.getConfidence() * segmentWeight;
            weight += segmentWeight;
        }
        return weight == 0L ? Double.NaN : weighted / weight;
    }

    private static long addMillis(long nanos, long millis) {
        long delta;
        try { delta = Math.multiplyExact(Math.max(1L, millis), 1_000_000L); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        long result = nanos + delta;
        return result < nanos ? Long.MAX_VALUE : result;
    }

    private static long nanosToMillis(long nanos) {
        if (nanos <= 0) return 0L;
        long millis = nanos / 1_000_000L;
        return millis == 0L ? 1L : millis;
    }
}
