package com.whisperonnx.asr;

import java.util.Objects;

/** Reassembles arbitrary audio reads into fixed-size VAD frames with bounded memory. */
public final class VadFrameAssembler {
    public interface FrameConsumer {
        /** The frame is reused after this callback and must not be retained. */
        void onFrame(byte[] frame, long endByteOffset);
    }

    private final byte[] frame;
    private int frameBytes;
    private long acceptedBytes;

    public VadFrameAssembler(int frameSizeBytes) {
        if (frameSizeBytes <= 0) throw new IllegalArgumentException("frameSizeBytes must be positive");
        frame = new byte[frameSizeBytes];
    }

    public void accept(byte[] source, int offset, int length, FrameConsumer consumer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(consumer, "consumer");
        if (offset < 0 || length < 0 || offset > source.length - length) {
            throw new IndexOutOfBoundsException("Invalid source range");
        }
        int sourceOffset = offset;
        int remaining = length;
        while (remaining > 0) {
            int copied = Math.min(frame.length - frameBytes, remaining);
            System.arraycopy(source, sourceOffset, frame, frameBytes, copied);
            frameBytes += copied;
            sourceOffset += copied;
            remaining -= copied;
            acceptedBytes += copied;
            if (frameBytes == frame.length) {
                consumer.onFrame(frame, acceptedBytes);
                frameBytes = 0;
            }
        }
    }

    public int getPendingByteCount() { return frameBytes; }
    public long getAcceptedByteCount() { return acceptedBytes; }
    public void reset() { frameBytes = 0; acceptedBytes = 0L; }
}
