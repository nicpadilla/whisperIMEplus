package com.whisperonnx.asr;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class WordReplacements {

    private static final String PREF_KEY = "wordReplacements";

    public static class Entry {
        public final String from;
        public final String to;

        public Entry(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    public static List<Entry> load(SharedPreferences sp) {
        List<Entry> entries = new ArrayList<>();
        String json = sp.getString(PREF_KEY, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                entries.add(new Entry(obj.getString("from"), obj.getString("to")));
            }
        } catch (JSONException e) {
            // Return empty list on parse error
        }
        return entries;
    }

    public static void save(SharedPreferences sp, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject obj = new JSONObject();
                obj.put("from", entry.from);
                obj.put("to", entry.to);
                array.put(obj);
            }
        } catch (JSONException e) {
            // Shouldn't happen with simple strings
        }
        sp.edit().putString(PREF_KEY, array.toString()).apply();
    }

    public static String applyReplacements(String text, List<Entry> entries) {
        if (text == null || text.isEmpty() || entries == null || entries.isEmpty()) {
            return text;
        }
        for (Entry entry : entries) {
            String quoted = Pattern.quote(entry.from);
            Pattern pattern = Pattern.compile("\\b" + quoted + "\\b", Pattern.CASE_INSENSITIVE);
            text = pattern.matcher(text).replaceAll(entry.to);
        }
        return text;
    }
}
