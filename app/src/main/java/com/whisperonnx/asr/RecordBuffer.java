package com.whisperonnx.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class RecordBuffer {
    // Static variable to store the byte array
    private static byte[] outputBuffer;
    // Segment boundary byte offsets (split points between segments)
    private static List<Integer> segmentBoundaries = new ArrayList<>();

    // Max samples per segment: 30 seconds at 16kHz
    private static final int MAX_SAMPLES_PER_SEGMENT = 16000 * 30;
    // Max bytes per segment: 30 seconds at 16kHz, 16-bit mono
    private static final int MAX_BYTES_PER_SEGMENT = MAX_SAMPLES_PER_SEGMENT * 2;
    // Min samples per segment: 0.3 seconds at 16kHz
    private static final int MIN_SAMPLES_PER_SEGMENT = 16000 * 3 / 10;

    // Synchronized method to set the byte array
    public static synchronized void setOutputBuffer(byte[] buffer) {
        outputBuffer = buffer;
    }

    // Synchronized method to get the byte array
    public static synchronized byte[] getOutputBuffer() {
        return outputBuffer;
    }

    public static synchronized void setSegmentBoundaries(List<Integer> boundaries) {
        segmentBoundaries = boundaries != null ? boundaries : new ArrayList<>();
    }

    /**
     * Computes final segment ranges as [startByte, endByte] pairs.
     * Splits any segment > 30s into 30s sub-segments.
     * Skips segments < 0.3s.
     */
    private static List<int[]> computeSegmentRanges() {
        List<int[]> ranges = new ArrayList<>();
        if (outputBuffer == null || outputBuffer.length == 0) return ranges;

        int totalBytes = outputBuffer.length;

        // Build raw split points: [0, b1, b2, ..., totalBytes]
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);
        for (int boundary : segmentBoundaries) {
            if (boundary > 0 && boundary < totalBytes) {
                splitPoints.add(boundary);
            }
        }
        splitPoints.add(totalBytes);

        // Process each raw segment
        for (int i = 0; i < splitPoints.size() - 1; i++) {
            int start = splitPoints.get(i);
            int end = splitPoints.get(i + 1);
            int segmentBytes = end - start;
            int segmentSamples = segmentBytes / 2;

            // Skip very short segments
            if (segmentSamples < MIN_SAMPLES_PER_SEGMENT) continue;

            // Split oversized segments into 30s chunks
            if (segmentBytes > MAX_BYTES_PER_SEGMENT) {
                int pos = start;
                while (pos < end) {
                    int chunkEnd = Math.min(pos + MAX_BYTES_PER_SEGMENT, end);
                    int chunkSamples = (chunkEnd - pos) / 2;
                    if (chunkSamples >= MIN_SAMPLES_PER_SEGMENT) {
                        ranges.add(new int[]{pos, chunkEnd});
                    }
                    pos = chunkEnd;
                }
            } else {
                ranges.add(new int[]{start, end});
            }
        }

        return ranges;
    }

    /**
     * Returns the number of segments after boundary processing.
     * Returns 1 if no boundaries were set (entire buffer = 1 segment).
     */
    public static synchronized int getSegmentCount() {
        List<int[]> ranges = computeSegmentRanges();
        return Math.max(ranges.size(), 1);
    }

    /**
     * Returns normalized float samples for a specific segment.
     * If no boundaries were set or index is 0 with only 1 segment, returns all samples.
     */
    public static synchronized float[] getSegmentSamples(int index) {
        List<int[]> ranges = computeSegmentRanges();

        if (ranges.isEmpty()) {
            // Fallback: return all samples (backward compatible)
            return getSamples();
        }

        if (index < 0 || index >= ranges.size()) return null;

        int[] range = ranges.get(index);
        int startByte = range[0];
        int endByte = range[1];
        int numSamples = (endByte - startByte) / 2;

        ByteBuffer byteBuffer = ByteBuffer.wrap(outputBuffer, startByte, endByte - startByte);
        byteBuffer.order(ByteOrder.nativeOrder());

        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            if (Math.abs(samples[i]) > maxAbsValue) {
                maxAbsValue = Math.abs(samples[i]);
            }
        }

        // Normalize the segment independently
        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;
    }

    public static float[] getSamples() {

        int numSamples = RecordBuffer.getOutputBuffer().length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(RecordBuffer.getOutputBuffer());
        byteBuffer.order(ByteOrder.nativeOrder());

        // Convert audio data to PCM_FLOAT format
        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            // Track the maximum absolute value
            if (Math.abs(samples[i]) > maxAbsValue) {
                maxAbsValue = Math.abs(samples[i]);
            }
        }

        // Normalize the samples
        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;

    }
}
