package com.whisperonnx.asr;

import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Final transcription plus recording-wide and per-segment metadata. */
public final class WhisperResult {
    private final String result;
    private final String language;
    private final Recognizer.Action task;
    private final double confidence;
    private final List<WhisperSegmentResult> segments;

    public WhisperResult(String result, String language, Recognizer.Action task) {
        this(result, language, task, Double.NaN, Collections.emptyList());
    }

    public WhisperResult(String result, String language, Recognizer.Action task,
                         double confidence, List<WhisperSegmentResult> segments) {
        this.result = result == null ? "" : result;
        this.language = language == null ? "??" : language;
        this.task = task;
        this.confidence = confidence;
        this.segments = segments == null || segments.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public String getResult() { return result; }
    public String getLanguage() { return language; }
    public Recognizer.Action getTask() { return task; }
    public double getConfidence() { return confidence; }
    public List<WhisperSegmentResult> getSegments() { return segments; }

    public WhisperResult withResult(String replacement) {
        return new WhisperResult(replacement, language, task, confidence, segments);
    }
}
