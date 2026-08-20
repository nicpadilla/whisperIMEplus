package com.whisperonnx.asr;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WhisperTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        File folder = context.getExternalFilesDir(null);
        String[] names = {
                "Whisper_initializer.onnx", "Whisper_encoder.onnx", "Whisper_decoder.onnx",
                "Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx",
                "Whisper_detokenizer.onnx"};
        for (String name : names) {
            try (FileOutputStream stream = new FileOutputStream(new File(folder, name))) {
                stream.write(1);
            }
        }
    }

    @Test public void multiSegmentJobProducesOneJoinedRequestScopedResult() throws Exception {
        AtomicInteger segment = new AtomicInteger();
        FakeEngine engine = new FakeEngine((jobId, index, language) ->
                index == 0
                        ? new TranscriptionJobRunner.SegmentRecognition("Hello", "en", 0.2)
                        : new TranscriptionJobRunner.SegmentRecognition("world.", "en", 0.8));
        Whisper whisper = new Whisper(context, ignored -> engine,
                new SegmentPlanner(), Executors.newSingleThreadExecutor());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<WhisperResult> result = new AtomicReference<>();
        AtomicReference<Long> resultJob = new AtomicReference<>();
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onResultReceived(long jobId, WhisperResult value) {
                resultJob.set(jobId);
                result.set(value);
            }
            @Override public void onWhisperEvent(WhisperEvent event) {
                if (event.getType() == WhisperEvent.Type.COMPLETED) completed.countDown();
            }
        });
        long jobId = whisper.start(recording(31 * 16000), ACTION_TRANSCRIBE, "auto");
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals(jobId, resultJob.get().longValue());
        assertEquals("Hello world.", result.get().getResult());
        assertEquals(2, result.get().getSegments().size());
        waitUntilIdle(whisper);
        assertFalse(whisper.isInProgress());
        whisper.close();
    }

    @Test public void cancellationStopsJobAndSuppressesLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        BlockingEngine engine = new BlockingEngine(entered);
        Whisper whisper = new Whisper(context, ignored -> engine,
                new SegmentPlanner(), Executors.newSingleThreadExecutor());
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onResultReceived(long jobId, WhisperResult result) {
                results.incrementAndGet();
            }
            @Override public void onWhisperEvent(WhisperEvent event) {
                if (event.getType() == WhisperEvent.Type.CANCELLED) cancelled.countDown();
            }
        });
        long jobId = whisper.start(recording(100), ACTION_TRANSCRIBE, "auto");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(whisper.cancel(jobId));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertEquals(0, results.get());
        whisper.close();
    }

    @Test public void modelReadinessFailureIsOneTypedTerminalError() throws Exception {
        FakeEngine engine = new FakeEngine((jobId, index, language) -> null);
        engine.readyFailure = new IllegalStateException("bad model");
        Whisper whisper = new Whisper(context, ignored -> engine,
                new SegmentPlanner(), Executors.newSingleThreadExecutor());
        CountDownLatch error = new CountDownLatch(1);
        AtomicReference<WhisperEvent.ErrorCode> code = new AtomicReference<>();
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onWhisperEvent(WhisperEvent event) {
                if (event.getType() == WhisperEvent.Type.ERROR && event.getJobId() > 0) {
                    code.set(event.getErrorCode());
                    error.countDown();
                }
            }
        });
        whisper.start(recording(100), ACTION_TRANSCRIBE, "en");
        assertTrue(error.await(2, TimeUnit.SECONDS));
        assertEquals(WhisperEvent.ErrorCode.MODEL_LOAD_FAILED, code.get());
        whisper.close();
    }

    private static RecordingData recording(int samples) {
        byte[] pcm = new byte[samples * 2];
        return RecordingData.copyOf(pcm, pcm.length, 16000, 1, 2,
                AudioFormat.ENCODING_PCM_16BIT, Collections.emptyList(),
                samples * 1000L / 16000L, "default", "default");
    }

    private static void waitUntilIdle(Whisper whisper) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (whisper.isInProgress() && System.nanoTime() < deadline) Thread.sleep(5L);
    }

    private interface ResultFactory {
        TranscriptionJobRunner.SegmentRecognition create(long jobId, int index, String language);
    }

    private static class FakeEngine implements Whisper.RecognitionEngine {
        final ResultFactory factory;
        volatile boolean closed;
        Exception readyFailure;
        FakeEngine(ResultFactory factory) { this.factory = factory; }
        @Override public void awaitReady(long timeoutMs) throws Exception {
            if (readyFailure != null) throw readyFailure;
        }
        @Override public TranscriptionJobRunner.SegmentRecognition recognize(
                long jobId, int segmentIndex, float[] samples, String language,
                com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.Action action,
                long timeoutMs, TranscriptionJobRunner.CancellationToken cancellation) throws Exception {
            return factory.create(jobId, segmentIndex, language);
        }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }

    private static final class BlockingEngine extends FakeEngine {
        private final CountDownLatch entered;
        private final AtomicBoolean closedFlag = new AtomicBoolean(false);
        BlockingEngine(CountDownLatch entered) { super((job, index, language) -> null); this.entered = entered; }
        @Override public TranscriptionJobRunner.SegmentRecognition recognize(
                long jobId, int segmentIndex, float[] samples, String language,
                com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.Action action,
                long timeoutMs, TranscriptionJobRunner.CancellationToken cancellation) throws Exception {
            entered.countDown();
            while (!cancellation.isCancelled() && !closedFlag.get()) Thread.sleep(2L);
            throw new java.util.concurrent.CancellationException("cancelled");
        }
        @Override public boolean isClosed() { return closedFlag.get(); }
        @Override public void close() { closedFlag.set(true); }
    }
}
