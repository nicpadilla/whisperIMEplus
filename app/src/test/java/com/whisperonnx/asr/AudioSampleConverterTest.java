package com.whisperonnx.asr;

import static org.junit.Assert.assertArrayEquals;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;

public class AudioSampleConverterTest {
    @Test public void convertsPcmWithoutPeakNormalizingQuietAudio() {
        ByteBuffer bytes = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort((short) 100).putShort((short) -200).putShort((short) 1000);
        RecordingData data = RecordingData.copyOf(bytes.array(), 6, 16000, 1, 2,
                AudioFormat.ENCODING_PCM_16BIT, Collections.emptyList(), 0,
                "default", "default");
        assertArrayEquals(new float[]{100 / 32768f, -200 / 32768f, 1000 / 32768f},
                AudioSampleConverter.toFloatSamples(data, new SegmentRange(0, 3)), 0.000001f);
    }
}
