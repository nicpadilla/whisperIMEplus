package com.whisperonnx.asr;

import java.util.Objects;

/** Inclusive start and exclusive end sample-frame range. */
public final class SegmentRange {
    private final int startSample;
    private final int endSample;

    public SegmentRange(int startSample, int endSample) {
        if (startSample < 0 || endSample <= startSample) {
            throw new IllegalArgumentException("Segment range must be non-empty and non-negative");
        }
        this.startSample = startSample;
        this.endSample = endSample;
    }

    public int getStartSample() { return startSample; }
    public int getEndSample() { return endSample; }
    public int lengthSamples() { return endSample - startSample; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SegmentRange)) return false;
        SegmentRange that = (SegmentRange) other;
        return startSample == that.startSample && endSample == that.endSample;
    }

    @Override public int hashCode() { return Objects.hash(startSample, endSample); }
    @Override public String toString() { return "[" + startSample + ", " + endSample + ")"; }
}
