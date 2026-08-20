package com.whisperonnx.asr;

/** Result and metadata for one planned recording segment. */
public final class WhisperSegmentResult {
    private final int segmentIndex;
    private final SegmentRange range;
    private final String text;
    private final String language;
    private final double confidence;

    public WhisperSegmentResult(int segmentIndex, SegmentRange range, String text,
                                String language, double confidence) {
        if (segmentIndex < 0 || range == null) {
            throw new IllegalArgumentException("segmentIndex and range are required");
        }
        this.segmentIndex = segmentIndex;
        this.range = range;
        this.text = text == null ? "" : text;
        this.language = language == null ? "??" : language;
        this.confidence = confidence;
    }

    public int getSegmentIndex() { return segmentIndex; }
    public SegmentRange getRange() { return range; }
    public String getText() { return text; }
    public String getLanguage() { return language; }
    public double getConfidence() { return confidence; }
}
