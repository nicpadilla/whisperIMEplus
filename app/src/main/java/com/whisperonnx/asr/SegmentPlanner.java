package com.whisperonnx.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plans deterministic, lossless Whisper-sized sample ranges. */
public final class SegmentPlanner {
    public static final int DEFAULT_MAX_SECONDS = 30;
    public static final int DEFAULT_MIN_PREFERRED_SECONDS = 5;
    public static final int DEFAULT_MIN_TAIL_SECONDS = 5;

    private final int maxSeconds;
    private final int minPreferredSeconds;
    private final int minTailSeconds;

    public SegmentPlanner() {
        this(DEFAULT_MAX_SECONDS, DEFAULT_MIN_PREFERRED_SECONDS, DEFAULT_MIN_TAIL_SECONDS);
    }

    public SegmentPlanner(int maxSeconds, int minPreferredSeconds, int minTailSeconds) {
        if (maxSeconds <= 0 || minPreferredSeconds < 0 || minTailSeconds < 0
                || minPreferredSeconds > maxSeconds || minTailSeconds > maxSeconds) {
            throw new IllegalArgumentException("Invalid segment planner durations");
        }
        this.maxSeconds = maxSeconds;
        this.minPreferredSeconds = minPreferredSeconds;
        this.minTailSeconds = minTailSeconds;
    }

    public List<SegmentRange> plan(RecordingData recording) {
        if (recording == null || recording.getSampleCount() <= 0) return Collections.emptyList();
        int totalSamples = recording.getSampleCount();
        int sampleRate = recording.getSampleRateHz();
        int maxSamples = Math.multiplyExact(sampleRate, maxSeconds);
        int minPreferredSamples = Math.multiplyExact(sampleRate, minPreferredSeconds);
        int minTailSamples = Math.multiplyExact(sampleRate, minTailSeconds);
        if (totalSamples <= maxSamples) {
            return Collections.singletonList(new SegmentRange(0, totalSamples));
        }

        int[] boundaries = boundarySamples(recording);
        List<SegmentRange> ranges = new ArrayList<>();
        int start = 0;
        while (totalSamples - start > maxSamples) {
            int latestAllowed = start + maxSamples;
            int earliestPreferred = Math.min(latestAllowed, start + Math.max(1, minPreferredSamples));
            int cut = latestBoundary(boundaries, earliestPreferred, latestAllowed);
            if (cut <= start) cut = latestAllowed;
            ranges.add(new SegmentRange(start, cut));
            start = cut;
        }
        ranges.add(new SegmentRange(start, totalSamples));
        rebalanceShortTail(ranges, boundaries, totalSamples, maxSamples, minTailSamples);
        assertInvariants(ranges, totalSamples, maxSamples);
        return Collections.unmodifiableList(ranges);
    }

    private static int[] boundarySamples(RecordingData recording) {
        List<Integer> bytes = recording.getSegmentBoundaryBytes();
        int[] samples = new int[bytes.size()];
        int frameSize = recording.getFrameSizeBytes();
        for (int index = 0; index < bytes.size(); index++) samples[index] = bytes.get(index) / frameSize;
        return samples;
    }

    private static int latestBoundary(int[] boundaries, int minimum, int maximum) {
        int selected = -1;
        for (int boundary : boundaries) {
            if (boundary > maximum) break;
            if (boundary >= minimum) selected = boundary;
        }
        return selected;
    }

    private static int nearestBoundary(int[] boundaries, int minimum, int maximum, int desired) {
        int selected = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int boundary : boundaries) {
            if (boundary < minimum) continue;
            if (boundary > maximum) break;
            int distance = Math.abs(boundary - desired);
            if (distance < bestDistance || (distance == bestDistance && boundary > selected)) {
                selected = boundary;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private static void rebalanceShortTail(List<SegmentRange> ranges, int[] boundaries,
                                           int totalSamples, int maxSamples, int minTailSamples) {
        if (ranges.size() < 2 || minTailSamples <= 0) return;
        SegmentRange tail = ranges.get(ranges.size() - 1);
        if (tail.lengthSamples() >= minTailSamples) return;

        SegmentRange previous = ranges.get(ranges.size() - 2);
        int combinedStart = previous.getStartSample();
        int minimumCut = Math.max(combinedStart + 1, totalSamples - maxSamples);
        int maximumCut = Math.min(combinedStart + maxSamples, totalSamples - minTailSamples);
        if (maximumCut < minimumCut) {
            maximumCut = Math.min(combinedStart + maxSamples, totalSamples - 1);
        }
        int desiredCut = clamp(totalSamples - minTailSamples, minimumCut, maximumCut);
        int naturalCut = nearestBoundary(boundaries, minimumCut, maximumCut, desiredCut);
        int cut = naturalCut > combinedStart ? naturalCut : desiredCut;
        ranges.set(ranges.size() - 2, new SegmentRange(combinedStart, cut));
        ranges.set(ranges.size() - 1, new SegmentRange(cut, totalSamples));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static void assertInvariants(List<SegmentRange> ranges, int totalSamples, int maxSamples) {
        if (ranges.isEmpty()) throw new IllegalStateException("Non-empty recording produced no ranges");
        int expectedStart = 0;
        for (SegmentRange range : ranges) {
            if (range.getStartSample() != expectedStart) {
                throw new IllegalStateException("Segment plan contains a gap or overlap");
            }
            if (range.lengthSamples() <= 0 || range.lengthSamples() > maxSamples) {
                throw new IllegalStateException("Segment length is outside model bounds");
            }
            expectedStart = range.getEndSample();
        }
        if (expectedStart != totalSamples) {
            throw new IllegalStateException("Segment plan does not cover the recording");
        }
    }
}
