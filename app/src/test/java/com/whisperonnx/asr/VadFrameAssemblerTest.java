package com.whisperonnx.asr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VadFrameAssemblerTest {
    @Test public void fragmentedAndOversizedReadsEmitEachFrameExactlyOnce() {
        VadFrameAssembler assembler = new VadFrameAssembler(4);
        byte[] input = new byte[13];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;
        List<byte[]> frames = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        VadFrameAssembler.FrameConsumer consumer = (frame, offset) -> {
            frames.add(Arrays.copyOf(frame, frame.length));
            offsets.add(offset);
        };
        assembler.accept(input, 0, 1, consumer);
        assembler.accept(input, 1, 7, consumer);
        assembler.accept(input, 8, 5, consumer);

        assertEquals(3, frames.size());
        assertArrayEquals(new byte[]{0, 1, 2, 3}, frames.get(0));
        assertArrayEquals(new byte[]{4, 5, 6, 7}, frames.get(1));
        assertArrayEquals(new byte[]{8, 9, 10, 11}, frames.get(2));
        assertEquals(Arrays.asList(4L, 8L, 12L), offsets);
        assertEquals(1, assembler.getPendingByteCount());
        assertEquals(13L, assembler.getAcceptedByteCount());
    }

    @Test public void longInputUsesBoundedPendingStorage() {
        VadFrameAssembler assembler = new VadFrameAssembler(960);
        byte[] read = new byte[4096];
        final int[] frames = {0};
        for (int i = 0; i < 2000; i++) {
            assembler.accept(read, 0, read.length, (frame, offset) -> frames[0]++);
            org.junit.Assert.assertTrue(assembler.getPendingByteCount() < 960);
        }
        assertEquals((2000 * 4096) / 960, frames[0]);
    }
}
