package com.whisperonnx.asr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import android.media.AudioFormat;

import org.junit.Test;

import java.util.Arrays;

public class RecordingDataTest {
    @Test public void copyFactoryProtectsPcmAndSanitizesBoundaries() {
        byte[] source = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        RecordingData data = RecordingData.copyOf(source, 8, 16000, 1, 2,
                AudioFormat.ENCODING_PCM_16BIT,
                Arrays.asList(7, 2, 2, -2, 99, 5), 1L, "default", "default");
        source[0] = 99;
        byte[] first = data.getPcmBytesCopy();
        byte[] second = data.getPcmBytesCopy();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, first);
        assertNotSame(first, second);
        assertEquals(Arrays.asList(2, 4, 6), data.getSegmentBoundaryBytes());
        assertEquals(4, data.getSampleCount());
    }
}
