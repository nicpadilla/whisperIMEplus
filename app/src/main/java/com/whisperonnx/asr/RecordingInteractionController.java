package com.whisperonnx.asr;

/** Pure touch/lifecycle state machine shared by all recording UI surfaces. */
public final class RecordingInteractionController {
    public enum State {
        IDLE,
        RECORDING_HELD,
        RECORDING_TOGGLED,
        STOPPING,
        PROCESSING,
        ERROR,
        DISPOSED
    }

    public interface Callbacks {
        long startRecording(boolean autoStopOnSilence);
        void requestStopRecording(long requestId);
        void cancelRecording(long requestId);
        long startTranscription(RecordingData recording);
        void cancelTranscription(long jobId);
        void onStateChanged(State state);
        void onTranscriptionResult(WhisperResult result);
        void onError(String message);
    }

    public static final long DEFAULT_TAP_THRESHOLD_MS = 300L;

    private final Callbacks callbacks;
    private final long tapThresholdMs;
    private State state = State.IDLE;
    private long activeRecordingId = -1L;
    private long activeJobId = -1L;
    private long downTimeMs;
    private boolean secondTapArmed;

    public RecordingInteractionController(Callbacks callbacks) {
        this(callbacks, DEFAULT_TAP_THRESHOLD_MS);
    }

    public RecordingInteractionController(Callbacks callbacks, long tapThresholdMs) {
        if (callbacks == null || tapThresholdMs < 0L) {
            throw new IllegalArgumentException("callbacks and a non-negative threshold are required");
        }
        this.callbacks = callbacks;
        this.tapThresholdMs = tapThresholdMs;
        callbacks.onStateChanged(state);
    }

    public State getState() { return state; }
    public long getActiveRecordingId() { return activeRecordingId; }
    public long getActiveJobId() { return activeJobId; }

    public boolean onTouchDown(long eventTimeMs) {
        if (state == State.DISPOSED || state == State.PROCESSING || state == State.STOPPING) {
            return false;
        }
        if (state == State.ERROR) transition(State.IDLE);
        downTimeMs = eventTimeMs;
        if (state == State.RECORDING_TOGGLED) {
            secondTapArmed = true;
            return true;
        }
        if (state != State.IDLE) return false;
        long requestId = callbacks.startRecording(false);
        if (requestId <= 0L) {
            fail("Unable to start recording");
            return false;
        }
        activeRecordingId = requestId;
        secondTapArmed = false;
        transition(State.RECORDING_HELD);
        return true;
    }

    public boolean onTouchUp(long eventTimeMs) {
        if (state == State.DISPOSED) return false;
        if (state == State.RECORDING_HELD) {
            long elapsed = Math.max(0L, eventTimeMs - downTimeMs);
            if (elapsed < tapThresholdMs) {
                transition(State.RECORDING_TOGGLED);
            } else {
                requestNormalStop();
            }
            return true;
        }
        if (state == State.RECORDING_TOGGLED && secondTapArmed) {
            secondTapArmed = false;
            requestNormalStop();
            return true;
        }
        return false;
    }

    public boolean onTouchCancel() {
        if (state == State.RECORDING_HELD && activeRecordingId > 0L) {
            long requestId = activeRecordingId;
            secondTapArmed = false;
            transition(State.STOPPING);
            callbacks.cancelRecording(requestId);
            return true;
        }
        if (state == State.RECORDING_TOGGLED && secondTapArmed) {
            secondTapArmed = false;
            return true;
        }
        return false;
    }

    /** Accessibility click mirrors a short tap: start toggled, or stop an existing toggle. */
    public boolean onAccessibilityClick(long eventTimeMs) {
        if (state == State.RECORDING_TOGGLED) {
            secondTapArmed = true;
            return onTouchUp(eventTimeMs);
        }
        if (!onTouchDown(eventTimeMs)) return false;
        return onTouchUp(eventTimeMs);
    }

    public boolean startAutomaticRecording() {
        if (state == State.ERROR) transition(State.IDLE);
        if (state != State.IDLE) return false;
        long requestId = callbacks.startRecording(true);
        if (requestId <= 0L) {
            fail("Unable to start automatic recording");
            return false;
        }
        activeRecordingId = requestId;
        transition(State.RECORDING_TOGGLED);
        return true;
    }

    public void onRecorderEvent(RecorderEvent event) {
        if (event == null || state == State.DISPOSED) return;
        if (event.getRequestId() != activeRecordingId) return;
        switch (event.getType()) {
            case CAPTURE_STARTED:
            case ROUTE_CHANGED:
            case SPEECH_STARTED:
                break;
            case COMPLETED:
                activeRecordingId = -1L;
                secondTapArmed = false;
                transition(State.PROCESSING);
                long jobId = callbacks.startTranscription(event.getRecording());
                if (jobId <= 0L) {
                    fail("Unable to start transcription");
                } else {
                    activeJobId = jobId;
                }
                break;
            case CANCELLED:
                activeRecordingId = -1L;
                secondTapArmed = false;
                transition(State.IDLE);
                break;
            case ERROR:
                activeRecordingId = -1L;
                secondTapArmed = false;
                fail(event.getMessage() == null ? "Recording failed" : event.getMessage());
                break;
            default:
                break;
        }
    }

    public void onWhisperEvent(WhisperEvent event) {
        if (event == null || state == State.DISPOSED) return;
        if (event.getJobId() <= 0L) return; // model-level events are rendered by the owner if desired
        if (event.getJobId() != activeJobId) return;
        switch (event.getType()) {
            case COMPLETED:
                activeJobId = -1L;
                transition(State.IDLE);
                break;
            case CANCELLED:
                activeJobId = -1L;
                transition(State.IDLE);
                break;
            case ERROR:
                activeJobId = -1L;
                fail(event.getMessage() == null ? "Transcription failed" : event.getMessage());
                break;
            default:
                break;
        }
    }

    public void onWhisperResult(long jobId, WhisperResult result) {
        if (state == State.DISPOSED || jobId != activeJobId || result == null) return;
        callbacks.onTranscriptionResult(result);
    }

    public void cancelActiveWork() {
        if (state == State.DISPOSED) return;
        long recordingId = activeRecordingId;
        long jobId = activeJobId;
        activeRecordingId = -1L;
        activeJobId = -1L;
        secondTapArmed = false;
        transition(State.IDLE);
        if (recordingId > 0L) callbacks.cancelRecording(recordingId);
        if (jobId > 0L) callbacks.cancelTranscription(jobId);
    }

    public void dispose() {
        if (state == State.DISPOSED) return;
        long recordingId = activeRecordingId;
        long jobId = activeJobId;
        activeRecordingId = -1L;
        activeJobId = -1L;
        secondTapArmed = false;
        transition(State.DISPOSED);
        if (recordingId > 0L) callbacks.cancelRecording(recordingId);
        if (jobId > 0L) callbacks.cancelTranscription(jobId);
    }

    private void requestNormalStop() {
        if (activeRecordingId <= 0L) {
            fail("No active recording request");
            return;
        }
        long requestId = activeRecordingId;
        secondTapArmed = false;
        transition(State.STOPPING);
        callbacks.requestStopRecording(requestId);
    }

    private void fail(String message) {
        transition(State.ERROR);
        callbacks.onError(message);
    }

    private void transition(State newState) {
        if (state == newState) return;
        state = newState;
        callbacks.onStateChanged(newState);
    }
}
