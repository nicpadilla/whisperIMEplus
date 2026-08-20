package com.whisperonnx.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Request-owned immutable PCM recording and capture metadata. */
public final class RecordingData {
    private final byte[] pcmBytes;
    private final int validByteCount;
    private final int sampleRateHz;
    private final int channelCount;
    private final int bytesPerSample;
    private final int audioEncoding;
    private final List<Integer> segmentBoundaryBytes;
    private final long captureDurationMs;
    private final String requestedRoute;
    private final String actualRoute;

    private RecordingData(byte[] pcmBytes, int validByteCount, int sampleRateHz,
                          int channelCount, int bytesPerSample, int audioEncoding,
                          Collection<Integer> boundaries, long captureDurationMs,
                          String requestedRoute, String actualRoute, boolean takeOwnership) {
        if (pcmBytes == null) throw new IllegalArgumentException("pcmBytes is required");
        if (sampleRateHz <= 0 || channelCount <= 0 || bytesPerSample <= 0) {
            throw new IllegalArgumentException("Invalid recording format");
        }
        int frameSize = Math.multiplyExact(channelCount, bytesPerSample);
        if (validByteCount < 0 || validByteCount > pcmBytes.length
                || validByteCount % frameSize != 0) {
            throw new IllegalArgumentException("validByteCount must be frame aligned and in range");
        }
        this.pcmBytes = takeOwnership ? pcmBytes : Arrays.copyOf(pcmBytes, validByteCount);
        this.validByteCount = validByteCount;
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.bytesPerSample = bytesPerSample;
        this.audioEncoding = audioEncoding;
        this.segmentBoundaryBytes = sanitizeBoundaries(boundaries, validByteCount, frameSize);
        this.captureDurationMs = Math.max(0L, captureDurationMs);
        this.requestedRoute = requestedRoute == null ? "default" : requestedRoute;
        this.actualRoute = actualRoute == null ? "unknown" : actualRoute;
    }

    static RecordingData takeOwnership(byte[] pcmBytes, int validByteCount, int sampleRateHz,
                                       int channelCount, int bytesPerSample, int audioEncoding,
                                       Collection<Integer> boundaries, long captureDurationMs,
                                       String requestedRoute, String actualRoute) {
        return new RecordingData(pcmBytes, validByteCount, sampleRateHz, channelCount,
                bytesPerSample, audioEncoding, boundaries, captureDurationMs,
                requestedRoute, actualRoute, true);
    }

    public static RecordingData copyOf(byte[] pcmBytes, int validByteCount, int sampleRateHz,
                                       int channelCount, int bytesPerSample, int audioEncoding,
                                       Collection<Integer> boundaries, long captureDurationMs,
                                       String requestedRoute, String actualRoute) {
        return new RecordingData(pcmBytes, validByteCount, sampleRateHz, channelCount,
                bytesPerSample, audioEncoding, boundaries, captureDurationMs,
                requestedRoute, actualRoute, false);
    }

    private static List<Integer> sanitizeBoundaries(Collection<Integer> boundaries,
                                                     int validBytes, int frameSize) {
        if (boundaries == null || boundaries.isEmpty()) return Collections.emptyList();
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer candidate : boundaries) {
            if (candidate == null) continue;
            int aligned = candidate - Math.floorMod(candidate, frameSize);
            if (aligned > 0 && aligned < validBytes) sorted.add(aligned);
        }
        return Collections.unmodifiableList(new ArrayList<>(sorted));
    }

    /** Zero-copy read-only PCM view for trusted package peers. */
    ByteBuffer pcmReadOnly() {
        ByteBuffer result = ByteBuffer.wrap(pcmBytes, 0, validByteCount)
                .asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        result.position(0);
        result.limit(validByteCount);
        return result;
    }

    public byte[] getPcmBytesCopy() { return Arrays.copyOf(pcmBytes, validByteCount); }
    public int getValidByteCount() { return validByteCount; }
    public int getSampleRateHz() { return sampleRateHz; }
    public int getChannelCount() { return channelCount; }
    public int getBytesPerSample() { return bytesPerSample; }
    public int getAudioEncoding() { return audioEncoding; }
    public int getFrameSizeBytes() { return Math.multiplyExact(channelCount, bytesPerSample); }
    public int getSampleCount() { return validByteCount / getFrameSizeBytes(); }
    public List<Integer> getSegmentBoundaryBytes() { return segmentBoundaryBytes; }
    public long getCaptureDurationMs() { return captureDurationMs; }
    public String getRequestedRoute() { return requestedRoute; }
    public String getActualRoute() { return actualRoute; }
}
