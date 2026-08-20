package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class SegmentPlannerTest {
    private static RecordingData recording(int samples, List<Integer> boundarySamples) {
        byte[] pcm = new byte[samples * 2];
        List<Integer> bytes = new ArrayList<>();
        for (int value : boundarySamples) bytes.add(value * 2);
        return RecordingData.copyOf(pcm, pcm.length, 16000, 1, 2,
                AudioFormat.ENCODING_PCM_16BIT, bytes,
                samples * 1000L / 16000L, "default", "default");
    }

    @Test public void justOverWindowNeverBypassesPlannedRange() {
        List<SegmentRange> ranges = new SegmentPlanner().plan(
                recording(30 * 16000 + 1, Collections.emptyList()));
        assertEquals(2, ranges.size());
        assertCoverage(ranges, 30 * 16000 + 1, 30 * 16000);
    }

    @Test public void shortTailIsRebalancedInsteadOfDropped() {
        int total = 61 * 16000;
        List<SegmentRange> ranges = new SegmentPlanner().plan(
                recording(total, Collections.emptyList()));
        assertCoverage(ranges, total, 30 * 16000);
        assertTrue(ranges.get(ranges.size() - 1).lengthSamples() >= 5 * 16000);
    }

    @Test public void naturalBoundaryIsPreferred() {
        int boundary = 28 * 16000;
        List<SegmentRange> ranges = new SegmentPlanner().plan(
                recording(40 * 16000, Arrays.asList(boundary)));
        assertEquals(boundary, ranges.get(0).getEndSample());
    }

    @Test public void generatedInputsPreserveCoverageAndBounds() {
        Random random = new Random(4L);
        SegmentPlanner planner = new SegmentPlanner();
        for (int iteration = 0; iteration < 500; iteration++) {
            int samples = 1 + random.nextInt(300 * 16000);
            List<Integer> boundaries = new ArrayList<>();
            for (int i = 0; i < 12; i++) boundaries.add(random.nextInt(samples + 200) - 100);
            List<SegmentRange> ranges = planner.plan(recording(samples, boundaries));
            assertCoverage(ranges, samples, 30 * 16000);
        }
    }

    private static void assertCoverage(List<SegmentRange> ranges, int total, int max) {
        int cursor = 0;
        for (SegmentRange range : ranges) {
            assertEquals(cursor, range.getStartSample());
            assertTrue(range.lengthSamples() > 0);
            assertTrue(range.lengthSamples() <= max);
            cursor = range.getEndSample();
        }
        assertEquals(total, cursor);
    }
}
