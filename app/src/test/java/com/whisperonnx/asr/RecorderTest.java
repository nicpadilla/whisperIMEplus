package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RecorderTest {
    @Test public void stopIsNonBlockingAndProducesOneCompletion() throws Exception {
        Recorder.CaptureBackend backend = (request, control, observer) -> {
            observer.onSpeechStarted();
            while (!control.shouldStop()) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2L));
            }
            return recording();
        };
        Recorder recorder = new Recorder(backend, Executors.newSingleThreadExecutor());
        List<RecorderEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminal = new CountDownLatch(1);
        recorder.setListener(event -> {
            events.add(event);
            if (event.isTerminal()) terminal.countDown();
        });
        long id = recorder.start(false);
        Thread.sleep(20L);
        long started = System.nanoTime();
        assertTrue(recorder.requestStop(id));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("requestStop blocked for " + elapsedMs + "ms", elapsedMs < 100L);
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        assertEquals(1L, events.stream().filter(RecorderEvent::isTerminal).count());
        assertEquals(RecorderEvent.Type.COMPLETED,
                events.stream().filter(RecorderEvent::isTerminal).findFirst().get().getType());
        recorder.close();
    }

    @Test public void cancelProducesCancellationAndSuppressesRecording() throws Exception {
        Recorder.CaptureBackend backend = (request, control, observer) -> {
            while (!control.shouldStop()) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2L));
            }
            return recording();
        };
        Recorder recorder = new Recorder(backend, Executors.newSingleThreadExecutor());
        CountDownLatch terminal = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<RecorderEvent> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        recorder.setListener(event -> {
            if (event.isTerminal()) { result.set(event); terminal.countDown(); }
        });
        long id = recorder.start(false);
        assertTrue(recorder.cancel(id));
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        assertEquals(RecorderEvent.Type.CANCELLED, result.get().getType());
        recorder.close();
    }

    @Test public void backendFailureStillProducesOneTypedError() throws Exception {
        Recorder.CaptureBackend backend = (request, control, observer) -> {
            throw new Recorder.CaptureFailure(RecorderEvent.ErrorCode.READ_FAILED, "broken");
        };
        Recorder recorder = new Recorder(backend, Executors.newSingleThreadExecutor());
        CountDownLatch terminal = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<RecorderEvent> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        recorder.setListener(event -> {
            if (event.isTerminal()) { result.set(event); terminal.countDown(); }
        });
        recorder.start(false);
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        assertEquals(RecorderEvent.ErrorCode.READ_FAILED, result.get().getErrorCode());
        recorder.close();
    }

    private static RecordingData recording() {
        return RecordingData.copyOf(new byte[6400], 6400, 16000, 1, 2, 2,
                Collections.emptyList(), 200L, "default", "default");
    }
}
