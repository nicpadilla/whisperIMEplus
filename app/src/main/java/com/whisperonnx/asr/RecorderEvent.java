package com.whisperonnx.asr;

/** Typed recorder lifecycle event associated with exactly one request. */
public final class RecorderEvent {
    public enum Type {
        CAPTURE_STARTED,
        ROUTE_CHANGED,
        SPEECH_STARTED,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    public enum ErrorCode {
        PERMISSION_DENIED,
        INITIALIZATION_FAILED,
        READ_FAILED,
        NO_AUDIO,
        INTERNAL_ERROR,
        RECORDER_CLOSED
    }

    private final Type type;
    private final long requestId;
    private final RecordingData recording;
    private final ErrorCode errorCode;
    private final String message;
    private final Throwable cause;
    private final String requestedRoute;
    private final String actualRoute;

    private RecorderEvent(Type type, long requestId, RecordingData recording,
                          ErrorCode errorCode, String message, Throwable cause,
                          String requestedRoute, String actualRoute) {
        this.type = type;
        this.requestId = requestId;
        this.recording = recording;
        this.errorCode = errorCode;
        this.message = message;
        this.cause = cause;
        this.requestedRoute = requestedRoute;
        this.actualRoute = actualRoute;
    }

    public static RecorderEvent captureStarted(long requestId) {
        return new RecorderEvent(Type.CAPTURE_STARTED, requestId,
                null, null, null, null, null, null);
    }

    public static RecorderEvent routeChanged(long requestId, String requestedRoute,
                                             String actualRoute) {
        return new RecorderEvent(Type.ROUTE_CHANGED, requestId, null, null, null, null,
                requestedRoute == null ? "default" : requestedRoute,
                actualRoute == null ? "unknown" : actualRoute);
    }

    public static RecorderEvent speechStarted(long requestId) {
        return new RecorderEvent(Type.SPEECH_STARTED, requestId,
                null, null, null, null, null, null);
    }

    public static RecorderEvent completed(long requestId, RecordingData recording) {
        if (recording == null) throw new IllegalArgumentException("recording is required");
        return new RecorderEvent(Type.COMPLETED, requestId, recording,
                null, null, null, recording.getRequestedRoute(), recording.getActualRoute());
    }

    public static RecorderEvent cancelled(long requestId) {
        return new RecorderEvent(Type.CANCELLED, requestId,
                null, null, null, null, null, null);
    }

    public static RecorderEvent error(long requestId, ErrorCode errorCode,
                                      String message, Throwable cause) {
        if (errorCode == null) throw new IllegalArgumentException("errorCode is required");
        return new RecorderEvent(Type.ERROR, requestId,
                null, errorCode, message, cause, null, null);
    }

    public Type getType() { return type; }
    public long getRequestId() { return requestId; }
    public RecordingData getRecording() { return recording; }
    public ErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public Throwable getCause() { return cause; }
    public String getRequestedRoute() { return requestedRoute; }
    public String getActualRoute() { return actualRoute; }

    public boolean isTerminal() {
        return type == Type.COMPLETED || type == Type.CANCELLED || type == Type.ERROR;
    }
}
