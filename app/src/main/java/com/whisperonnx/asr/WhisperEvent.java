package com.whisperonnx.asr;

/** Typed model/job lifecycle event associated with one transcription request when applicable. */
public final class WhisperEvent {
    public enum Type {
        MODEL_LOADING,
        MODEL_READY,
        PROCESSING_STARTED,
        SEGMENT_STARTED,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    public enum ErrorCode {
        MODEL_NOT_FOUND,
        MODEL_LOAD_FAILED,
        MODEL_LOAD_TIMEOUT,
        RECOGNITION_FAILED,
        SEGMENT_TIMEOUT,
        JOB_TIMEOUT,
        ENGINE_BUSY,
        WHISPER_CLOSED,
        INTERNAL_ERROR
    }

    private final Type type;
    private final long jobId;
    private final int segmentIndex;
    private final int segmentCount;
    private final ErrorCode errorCode;
    private final String message;
    private final Throwable cause;

    private WhisperEvent(Type type, long jobId, int segmentIndex, int segmentCount,
                         ErrorCode errorCode, String message, Throwable cause) {
        this.type = type;
        this.jobId = jobId;
        this.segmentIndex = segmentIndex;
        this.segmentCount = segmentCount;
        this.errorCode = errorCode;
        this.message = message;
        this.cause = cause;
    }

    public static WhisperEvent modelLoading() {
        return new WhisperEvent(Type.MODEL_LOADING, -1L, -1, 0, null, null, null);
    }

    public static WhisperEvent modelReady() {
        return new WhisperEvent(Type.MODEL_READY, -1L, -1, 0, null, null, null);
    }

    public static WhisperEvent processingStarted(long jobId, int segmentCount) {
        return new WhisperEvent(Type.PROCESSING_STARTED, jobId, -1, segmentCount,
                null, null, null);
    }

    public static WhisperEvent segmentStarted(long jobId, int segmentIndex, int segmentCount) {
        return new WhisperEvent(Type.SEGMENT_STARTED, jobId, segmentIndex, segmentCount,
                null, null, null);
    }

    public static WhisperEvent completed(long jobId, int segmentCount) {
        return new WhisperEvent(Type.COMPLETED, jobId, segmentCount - 1, segmentCount,
                null, null, null);
    }

    public static WhisperEvent cancelled(long jobId) {
        return new WhisperEvent(Type.CANCELLED, jobId, -1, 0, null, null, null);
    }

    public static WhisperEvent error(long jobId, ErrorCode code, String message, Throwable cause) {
        return new WhisperEvent(Type.ERROR, jobId, -1, 0, code, message, cause);
    }

    public Type getType() { return type; }
    public long getJobId() { return jobId; }
    public int getSegmentIndex() { return segmentIndex; }
    public int getSegmentCount() { return segmentCount; }
    public ErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public Throwable getCause() { return cause; }
}
