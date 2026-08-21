package com.whisperonnx.asr;

import java.nio.ByteBuffer;

/** Converts PCM16 frames to model input without per-segment peak amplification. */
public final class AudioSampleConverter {
    private AudioSampleConverter() {}

    public static float[] toFloatSamples(RecordingData recording, SegmentRange range) {
        if (recording == null || range == null) {
            throw new IllegalArgumentException("recording and range are required");
        }
        if (recording.getBytesPerSample() != 2) {
            throw new IllegalArgumentException("Only PCM16 recordings are supported");
        }
        if (range.getEndSample() > recording.getSampleCount()) {
            throw new IllegalArgumentException("Range exceeds recording");
        }
        int channels = recording.getChannelCount();
        int byteOffset = Math.multiplyExact(range.getStartSample(), recording.getFrameSizeBytes());
        ByteBuffer bytes = recording.pcmReadOnly();
        bytes.position(byteOffset);
        float[] samples = new float[range.lengthSamples()];
        for (int frame = 0; frame < samples.length; frame++) {
            float mixed = 0.0f;
            for (int channel = 0; channel < channels; channel++) {
                mixed += bytes.getShort() / 32768.0f;
            }
            samples[frame] = mixed / channels;
        }
        return samples;
    }
}
