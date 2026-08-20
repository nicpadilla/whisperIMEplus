package com.whisperonnx.asr;

import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic whitespace/punctuation-aware joining for segment results. */
public final class TranscriptJoiner {
    private static final Pattern TOKEN = Pattern.compile("\\S+\\s*");
    private static final Pattern EDGE_SYMBOLS = Pattern.compile("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$");
    private static final int MAX_OVERLAP_WORDS = 8;

    private TranscriptJoiner() {}

    public static String join(List<String> segments) { return join(segments, false); }

    public static String join(List<String> segments, boolean overlapAware) {
        StringBuilder result = new StringBuilder();
        if (segments == null) return "";
        for (String segment : segments) append(result, segment, overlapAware);
        return result.toString().trim();
    }

    public static void append(StringBuilder result, String segment, boolean overlapAware) {
        if (result == null || segment == null) return;
        String current = segment.trim();
        if (current.isEmpty() || Recognizer.UNDEFINED_TEXT.equals(current)) return;
        if (overlapAware && result.length() > 0) {
            current = removeConservativeOverlap(result.toString(), current);
            if (current.isEmpty()) return;
        }
        if (result.length() > 0 && needsSpace(result, current)) result.append(' ');
        result.append(current);
    }

    private static boolean needsSpace(StringBuilder previous, String current) {
        int last = previous.codePointBefore(previous.length());
        if (Character.isWhitespace(last) || "([{\"'“‘".indexOf(last) >= 0) return false;
        int first = current.codePointAt(0);
        return ".,!?;:%)]}’”".indexOf(first) < 0;
    }

    static String removeConservativeOverlap(String previous, String current) {
        List<String> previousTokens = tokens(previous);
        List<TokenSlice> currentTokens = tokenSlices(current);
        int max = Math.min(MAX_OVERLAP_WORDS, Math.min(previousTokens.size(), currentTokens.size()));
        int overlap = 0;
        for (int count = max; count >= 2; count--) {
            boolean matches = true;
            for (int index = 0; index < count; index++) {
                String left = normalize(previousTokens.get(previousTokens.size() - count + index));
                String right = normalize(currentTokens.get(index).token);
                if (left.isEmpty() || !left.equals(right)) { matches = false; break; }
            }
            if (matches) { overlap = count; break; }
        }
        if (overlap == 0) return current;
        return current.substring(currentTokens.get(overlap - 1).end).trim();
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) result.add(matcher.group().trim());
        return result;
    }

    private static List<TokenSlice> tokenSlices(String text) {
        List<TokenSlice> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) result.add(new TokenSlice(matcher.group().trim(), matcher.end()));
        return result;
    }

    private static String normalize(String token) {
        return EDGE_SYMBOLS.matcher(token).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static final class TokenSlice {
        final String token;
        final int end;
        TokenSlice(String token, int end) { this.token = token; this.end = end; }
    }
}
