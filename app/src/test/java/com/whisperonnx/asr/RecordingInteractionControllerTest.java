package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RecordingInteractionControllerTest {
    @Test public void shortTapTogglesAndSecondTapStopsSameRequest() {
        FakeCallbacks callbacks = new FakeCallbacks();
        RecordingInteractionController controller = new RecordingInteractionController(callbacks, 300L);
        controller.onTouchDown(0L);
        controller.onTouchUp(100L);
        assertEquals(RecordingInteractionController.State.RECORDING_TOGGLED, controller.getState());
        assertEquals(1L, controller.getActiveRecordingId());
        controller.onTouchDown(500L);
        controller.onTouchUp(520L);
        assertEquals(RecordingInteractionController.State.STOPPING, controller.getState());
        assertEquals(java.util.Collections.singletonList(1L), callbacks.normalStops);
    }

    @Test public void longPressAndCancelHaveDefinedBehavior() {
        FakeCallbacks callbacks = new FakeCallbacks();
        RecordingInteractionController controller = new RecordingInteractionController(callbacks, 300L);
        controller.onTouchDown(0L);
        controller.onTouchUp(400L);
        assertEquals(RecordingInteractionController.State.STOPPING, controller.getState());
        controller.onRecorderEvent(RecorderEvent.completed(1L, recording()));
        assertEquals(RecordingInteractionController.State.PROCESSING, controller.getState());

        FakeCallbacks cancelled = new FakeCallbacks();
        RecordingInteractionController other = new RecordingInteractionController(cancelled, 300L);
        other.onTouchDown(0L);
        other.onTouchCancel();
        assertEquals(RecordingInteractionController.State.STOPPING, other.getState());
        assertEquals(java.util.Collections.singletonList(1L), cancelled.cancellations);
        other.onRecorderEvent(RecorderEvent.cancelled(1L));
        assertEquals(RecordingInteractionController.State.IDLE, other.getState());
    }

    @Test public void staleEventsAndLifecycleCancellationCannotReviveState() {
        FakeCallbacks callbacks = new FakeCallbacks();
        RecordingInteractionController controller = new RecordingInteractionController(callbacks);
        controller.onTouchDown(0L);
        controller.cancelActiveWork();
        controller.onRecorderEvent(RecorderEvent.completed(1L, recording()));
        assertEquals(RecordingInteractionController.State.IDLE, controller.getState());
        controller.dispose();
        controller.onRecorderEvent(RecorderEvent.cancelled(1L));
        assertEquals(RecordingInteractionController.State.DISPOSED, controller.getState());
    }

    private static RecordingData recording() {
        return RecordingData.copyOf(new byte[6400], 6400, 16000, 1, 2, 2,
                java.util.Collections.emptyList(), 200, "default", "default");
    }

    private static final class FakeCallbacks implements RecordingInteractionController.Callbacks {
        long nextRecording = 1L;
        long nextJob = 10L;
        final List<Long> normalStops = new ArrayList<>();
        final List<Long> cancellations = new ArrayList<>();
        RecordingInteractionController.State state;

        @Override public long startRecording(boolean autoStopOnSilence) { return nextRecording++; }
        @Override public void requestStopRecording(long requestId) { normalStops.add(requestId); }
        @Override public void cancelRecording(long requestId) { cancellations.add(requestId); }
        @Override public long startTranscription(RecordingData recording) { return nextJob++; }
        @Override public void cancelTranscription(long jobId) { }
        @Override public void onStateChanged(RecordingInteractionController.State state) { this.state = state; }
        @Override public void onTranscriptionResult(WhisperResult result) { }
        @Override public void onError(String message) { }
    }
}
