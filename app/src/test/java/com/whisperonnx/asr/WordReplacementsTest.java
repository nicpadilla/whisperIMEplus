package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WordReplacementsTest {
    private SharedPreferences preferences;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
    }

    @Test public void replacementTextIsLiteralAndRulesDoNotCascade() {
        List<WordReplacements.Entry> entries = Arrays.asList(
                new WordReplacements.Entry("alpha", "$5\\folder"),
                new WordReplacements.Entry("beta", "alpha"),
                new WordReplacements.Entry("$5", "changed"));
        assertEquals("$5\\folder alpha", WordReplacements.applyReplacements("alpha beta", entries));
    }

    @Test public void unicodeBoundariesAndLongestOverlapAreDeterministic() {
        List<WordReplacements.Entry> entries = Arrays.asList(
                new WordReplacements.Entry("мир", "world"),
                new WordReplacements.Entry("New", "N"),
                new WordReplacements.Entry("New York", "NYC"));
        assertEquals("world мирный NYC", WordReplacements.applyReplacements(
                "МИР мирный New York", entries));
    }

    @Test public void malformedLegacyStorageRecoversValidEntriesAndMigratesOnLoad() {
        preferences.edit().putString("wordReplacements",
                "[{\"from\":\"Saira\",\"to\":\"Sayra\"},{\"from\":7},{\"bad\":true}]").commit();
        List<WordReplacements.Entry> loaded = WordReplacements.load(preferences);
        assertEquals(1, loaded.size());
        assertEquals("Sayra", WordReplacements.apply(preferences, "Saira"));
        String migrated = preferences.getString("wordReplacements", "");
        assertTrue(migrated.contains("\"version\":2"));
        assertTrue(migrated.contains(loaded.get(0).id));
        // Reloading from persisted v2 data keeps the same stable rule identity.
        assertEquals(loaded.get(0).id, WordReplacements.load(preferences).get(0).id);
        assertEquals("Sayra", WordReplacements.apply(preferences, "Saira"));
    }

    @Test public void duplicateSourceValidationIsCaseInsensitive() {
        List<WordReplacements.Entry> entries = Arrays.asList(
                new WordReplacements.Entry("One Phrase", "x"));
        assertFalse(WordReplacements.validateUniqueSource(entries, null, "one phrase").isValid());
        assertTrue(WordReplacements.validateUniqueSource(entries, entries.get(0).id,
                "one phrase").isValid());
    }
}
