package com.whisperonnx.asr;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned persistence and deterministic single-pass evaluation of literal replacement rules. */
public final class WordReplacements {
    private static final String TAG = "WordReplacements";
    private static final String PREF_KEY = "wordReplacements";
    private static final int STORAGE_VERSION = 2;
    private static final int MATCH_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String WORD_CLASS = "\\p{L}\\p{M}\\p{N}_";
    private static final WeakHashMap<SharedPreferences, CacheEntry> CACHE = new WeakHashMap<>();

    private WordReplacements() { }

    public static final class Entry {
        public final String id;
        public final String from;
        public final String to;
        public final boolean enabled;

        public Entry(String from, String to) {
            this(UUID.randomUUID().toString(), from, to, true);
        }

        public Entry(String id, String from, String to, boolean enabled) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id is required");
            if (from == null || from.trim().isEmpty()) throw new IllegalArgumentException("from is required");
            if (to == null) throw new IllegalArgumentException("to is required");
            this.id = id;
            this.from = from.trim();
            this.to = to;
            this.enabled = enabled;
        }

        public Entry withText(String newFrom, String newTo) {
            return new Entry(id, newFrom, newTo, enabled);
        }

        public Entry withEnabled(boolean newEnabled) {
            return new Entry(id, from, to, newEnabled);
        }
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final String conflictingEntryId;
        private final String message;

        private ValidationResult(boolean valid, String conflictingEntryId, String message) {
            this.valid = valid;
            this.conflictingEntryId = conflictingEntryId;
            this.message = message;
        }

        public static ValidationResult valid() { return new ValidationResult(true, null, null); }
        public static ValidationResult invalid(String id, String message) {
            return new ValidationResult(false, id, message);
        }
        public boolean isValid() { return valid; }
        public String getConflictingEntryId() { return conflictingEntryId; }
        public String getMessage() { return message; }
    }

    public static List<Entry> load(SharedPreferences preferences) {
        if (preferences == null) return Collections.emptyList();
        String raw = preferences.getString(PREF_KEY, "[]");
        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(preferences);
            if (cached != null && cached.raw.equals(raw)) return cached.entries;
        }
        ParsedRules parsed = parse(raw);
        synchronized (CACHE) {
            CACHE.put(preferences, new CacheEntry(raw, parsed.entries, parsed.compiled));
        }
        return parsed.entries;
    }

    public static void save(SharedPreferences preferences, List<Entry> entries) {
        if (preferences == null) throw new IllegalArgumentException("preferences are required");
        List<Entry> safeEntries = sanitizeEntries(entries);
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put("version", STORAGE_VERSION);
            for (Entry entry : safeEntries) {
                JSONObject object = new JSONObject();
                object.put("id", entry.id);
                object.put("from", entry.from);
                object.put("to", entry.to);
                object.put("enabled", entry.enabled);
                array.put(object);
            }
            root.put("entries", array);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to serialize replacement rules", impossible);
        }
        String raw = root.toString();
        preferences.edit().putString(PREF_KEY, raw).apply();
        CompiledRuleSet compiled = compile(safeEntries);
        synchronized (CACHE) {
            CACHE.put(preferences, new CacheEntry(raw, safeEntries, compiled));
        }
    }

    public static String apply(SharedPreferences preferences, String text) {
        if (text == null || text.isEmpty() || preferences == null) return text;
        String raw = preferences.getString(PREF_KEY, "[]");
        CompiledRuleSet compiled;
        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(preferences);
            if (cached != null && cached.raw.equals(raw)) {
                compiled = cached.compiled;
            } else {
                ParsedRules parsed = parse(raw);
                CACHE.put(preferences, new CacheEntry(raw, parsed.entries, parsed.compiled));
                compiled = parsed.compiled;
            }
        }
        return compiled.apply(text);
    }

    /** Compatibility helper for callers/tests that already hold an entry list. */
    public static String applyReplacements(String text, List<Entry> entries) {
        if (text == null || text.isEmpty()) return text;
        return compile(sanitizeEntries(entries)).apply(text);
    }

    public static ValidationResult validateUniqueSource(List<Entry> entries,
                                                        String editedEntryId,
                                                        String proposedSource) {
        if (proposedSource == null || proposedSource.trim().isEmpty()) {
            return ValidationResult.invalid(null, "Source phrase is required");
        }
        String normalized = normalizeSource(proposedSource);
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.id.equals(editedEntryId)) continue;
                if (normalizeSource(entry.from).equals(normalized)) {
                    return ValidationResult.invalid(entry.id,
                            "A rule for this source phrase already exists");
                }
            }
        }
        return ValidationResult.valid();
    }

    private static ParsedRules parse(String raw) {
        List<Entry> entries = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) raw = "[]";
        try {
            String trimmed = raw.trim();
            JSONArray array;
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed); // legacy v1
            } else {
                JSONObject root = new JSONObject(trimmed);
                array = root.optJSONArray("entries");
                if (array == null) array = new JSONArray();
            }
            Set<String> usedIds = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                Object value = array.opt(index);
                if (!(value instanceof JSONObject)) {
                    Log.w(TAG, "Skipping malformed rule at index " + index);
                    continue;
                }
                JSONObject object = (JSONObject) value;
                try {
                    String from = object.getString("from").trim();
                    String to = object.getString("to");
                    if (from.isEmpty()) throw new JSONException("empty source");
                    String id = object.optString("id", "").trim();
                    if (id.isEmpty() || usedIds.contains(id)) id = UUID.randomUUID().toString();
                    usedIds.add(id);
                    boolean enabled = object.has("enabled") ? object.optBoolean("enabled", true) : true;
                    entries.add(new Entry(id, from, to, enabled));
                } catch (JSONException | IllegalArgumentException malformed) {
                    Log.w(TAG, "Skipping malformed rule at index " + index, malformed);
                }
            }
        } catch (JSONException malformedRoot) {
            Log.e(TAG, "Unable to parse replacement rule storage", malformedRoot);
        }
        List<Entry> immutable = Collections.unmodifiableList(entries);
        return new ParsedRules(immutable, compile(immutable));
    }

    private static List<Entry> sanitizeEntries(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<Entry> safe = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null || entry.from.trim().isEmpty()) continue;
            String id = entry.id;
            if (!ids.add(id)) id = UUID.randomUUID().toString();
            safe.add(id.equals(entry.id) ? entry
                    : new Entry(id, entry.from, entry.to, entry.enabled));
        }
        return Collections.unmodifiableList(safe);
    }

    private static CompiledRuleSet compile(List<Entry> entries) {
        List<CompiledRule> rules = new ArrayList<>();
        for (int order = 0; order < entries.size(); order++) {
            Entry entry = entries.get(order);
            if (!entry.enabled || entry.from.isEmpty()) continue;
            String expression = "(?<![" + WORD_CLASS + "])" + Pattern.quote(entry.from)
                    + "(?![" + WORD_CLASS + "])";
            rules.add(new CompiledRule(entry, order, Pattern.compile(expression, MATCH_FLAGS)));
        }
        return new CompiledRuleSet(Collections.unmodifiableList(rules));
    }

    private static String normalizeSource(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ParsedRules {
        final List<Entry> entries;
        final CompiledRuleSet compiled;
        ParsedRules(List<Entry> entries, CompiledRuleSet compiled) {
            this.entries = entries;
            this.compiled = compiled;
        }
    }

    private static final class CacheEntry {
        final String raw;
        final List<Entry> entries;
        final CompiledRuleSet compiled;
        CacheEntry(String raw, List<Entry> entries, CompiledRuleSet compiled) {
            this.raw = raw;
            this.entries = entries;
            this.compiled = compiled;
        }
    }

    private static final class CompiledRule {
        final Entry entry;
        final int order;
        final Pattern pattern;
        CompiledRule(Entry entry, int order, Pattern pattern) {
            this.entry = entry;
            this.order = order;
            this.pattern = pattern;
        }
    }

    private static final class Candidate {
        final int start;
        final int end;
        final int order;
        final String replacement;

        Candidate(int start, int end, int order, String replacement) {
            this.start = start;
            this.end = end;
            this.order = order;
            this.replacement = replacement;
        }

        int length() { return end - start; }
        boolean overlaps(Candidate other) { return start < other.end && other.start < end; }
    }

    private static final class CompiledRuleSet {
        final List<CompiledRule> rules;
        CompiledRuleSet(List<CompiledRule> rules) { this.rules = rules; }

        String apply(String input) {
            if (rules.isEmpty() || input.isEmpty()) return input;
            List<Candidate> candidates = new ArrayList<>();
            for (CompiledRule rule : rules) {
                Matcher matcher = rule.pattern.matcher(input);
                while (matcher.find()) {
                    candidates.add(new Candidate(matcher.start(), matcher.end(),
                            rule.order, rule.entry.to));
                }
            }
            if (candidates.isEmpty()) return input;

            // Select longest overlapping source first; persisted rule order resolves ties.
            candidates.sort(Comparator
                    .comparingInt(Candidate::length).reversed()
                    .thenComparingInt(candidate -> candidate.order)
                    .thenComparingInt(candidate -> candidate.start));
            List<Candidate> selected = new ArrayList<>();
            for (Candidate candidate : candidates) {
                boolean conflict = false;
                for (Candidate existing : selected) {
                    if (candidate.overlaps(existing)) { conflict = true; break; }
                }
                if (!conflict) selected.add(candidate);
            }
            selected.sort(Comparator.comparingInt(candidate -> candidate.start));

            StringBuilder output = new StringBuilder(input.length());
            int cursor = 0;
            for (Candidate candidate : selected) {
                if (candidate.start < cursor) continue;
                output.append(input, cursor, candidate.start);
                // Direct append treats '$' and backslashes literally and prevents cascading.
                output.append(candidate.replacement);
                cursor = candidate.end;
            }
            output.append(input, cursor, input.length());
            return output.toString();
        }
    }
}
