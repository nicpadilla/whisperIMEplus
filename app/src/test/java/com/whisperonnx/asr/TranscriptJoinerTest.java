package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class TranscriptJoinerTest {
    @Test public void joinsPunctuationWithoutDoubledSpaces() {
        assertEquals("Hello, world!", TranscriptJoiner.join(
                Arrays.asList("Hello", ",", "world", "!")));
    }

    @Test public void filtersUndefinedAndConservativelyRemovesOverlap() {
        assertEquals("The quick brown fox jumps high.", TranscriptJoiner.join(
                Arrays.asList("The quick brown fox", "brown fox jumps high."), true));
        assertEquals("Hello", TranscriptJoiner.join(Arrays.asList("[(und)]", "Hello")));
    }
}
