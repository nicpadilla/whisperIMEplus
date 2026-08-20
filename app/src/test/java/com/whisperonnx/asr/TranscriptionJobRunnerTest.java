package com.whisperonnx.asr;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TranscriptionJobRunnerTest {
    private static RecordingData recording(int samples) {
        byte[] pcm = new byte[samples * 2];
        return RecordingData.copyOf(pcm, pcm.length, 16000, 1, 2,
                AudioFormat.ENCODING_PCM_16BIT, Collections.emptyList(), 0,
                "default", "default");
    }

    @Test public void reusesDetectedLanguageAndAggregatesMetadata() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.List<String> requestedLanguages = new java.util.ArrayList<>();
        TranscriptionJobRunner runner = new TranscriptionJobRunner(
                (job, segment, samples, language, action, timeout, cancellation) -> {
                    requestedLanguages.add(language);
                    int call = calls.getAndIncrement();
                    return call == 0
                            ? new TranscriptionJobRunner.SegmentRecognition("Hello", "en", 0.25)
                            : new TranscriptionJobRunner.SegmentRecognition("world.", "en", 0.75);
                });
        RecordingData data = recording(20);
        List<SegmentRange> ranges = Arrays.asList(
                new SegmentRange(0, 5), new SegmentRange(5, 20));
        WhisperResult result = runner.run(new TranscriptionJobRunner.JobRequest(
                        1L, data, ACTION_TRANSCRIBE, "auto", ranges,
                        1000L, 5000L, false),
                new TranscriptionJobRunner.CancellationToken(), null);

        assertEquals(Arrays.asList("auto", "en"), requestedLanguages);
        assertEquals("Hello world.", result.getResult());
        assertEquals("en", result.getLanguage());
        assertEquals((0.25 * 5 + 0.75 * 15) / 20.0, result.getConfidence(), 0.000001);
        assertEquals(2, result.getSegments().size());
    }

    @Test public void cancellationStopsLaterSegments() {
        AtomicInteger calls = new AtomicInteger();
        TranscriptionJobRunner.CancellationToken cancellation =
                new TranscriptionJobRunner.CancellationToken();
        TranscriptionJobRunner runner = new TranscriptionJobRunner(
                (job, segment, samples, language, action, timeout, token) -> {
                    calls.incrementAndGet();
                    cancellation.cancel();
                    return new TranscriptionJobRunner.SegmentRecognition("one", "en", 0.1);
                });
        try {
            runner.run(new TranscriptionJobRunner.JobRequest(1L, recording(20),
                            ACTION_TRANSCRIBE, "auto",
                            Arrays.asList(new SegmentRange(0, 10), new SegmentRange(10, 20)),
                            1000L, 5000L, false), cancellation, null);
            org.junit.Assert.fail("Expected cancellation");
        } catch (TranscriptionJobRunner.JobFailure failure) {
            assertEquals(TranscriptionJobRunner.FailureReason.CANCELLED, failure.getReason());
        }
        assertEquals(1, calls.get());
    }
}
