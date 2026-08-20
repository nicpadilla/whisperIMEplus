package com.whisperonnx.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure stable-ID editor model for replacement-rule UI and migration tests. */
public final class WordReplacementEditorModel {
    private final List<WordReplacements.Entry> entries;

    public WordReplacementEditorModel(List<WordReplacements.Entry> initialEntries) {
        entries = new ArrayList<>();
        if (initialEntries != null) entries.addAll(initialEntries);
    }

    public List<WordReplacements.Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public WordReplacements.ValidationResult add(String from, String to) {
        WordReplacements.ValidationResult validation =
                WordReplacements.validateUniqueSource(entries, null, from);
        if (!validation.isValid()) return validation;
        entries.add(new WordReplacements.Entry(from, to));
        return WordReplacements.ValidationResult.valid();
    }

    public WordReplacements.ValidationResult update(String id, String from, String to) {
        int index = indexOf(id);
        if (index < 0) return WordReplacements.ValidationResult.invalid(null, "Rule no longer exists");
        WordReplacements.ValidationResult validation =
                WordReplacements.validateUniqueSource(entries, id, from);
        if (!validation.isValid()) return validation;
        entries.set(index, entries.get(index).withText(from, to));
        return WordReplacements.ValidationResult.valid();
    }

    public boolean setEnabled(String id, boolean enabled) {
        int index = indexOf(id);
        if (index < 0) return false;
        entries.set(index, entries.get(index).withEnabled(enabled));
        return true;
    }

    public boolean delete(String id) {
        int index = indexOf(id);
        if (index < 0) return false;
        entries.remove(index);
        return true;
    }

    public boolean move(String id, int delta) {
        int from = indexOf(id);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= entries.size()) return false;
        WordReplacements.Entry entry = entries.remove(from);
        entries.add(to, entry);
        return true;
    }

    public WordReplacements.Entry find(String id) {
        int index = indexOf(id);
        return index < 0 ? null : entries.get(index);
    }

    private int indexOf(String id) {
        if (id == null) return -1;
        for (int index = 0; index < entries.size(); index++) {
            if (id.equals(entries.get(index).id)) return index;
        }
        return -1;
    }
}
