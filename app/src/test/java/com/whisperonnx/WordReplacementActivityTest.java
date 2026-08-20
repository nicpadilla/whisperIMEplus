package com.whisperonnx;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WordReplacementActivityTest {
    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().putString("wordReplacements",
                "[{\"from\":\"Saira\",\"to\":\"Sayra\"}]").commit();
    }

    @Test public void legacyRulesSurviveCreationAndRecreation() {
        WordReplacementActivity activity = Robolectric.buildActivity(
                WordReplacementActivity.class).setup().get();
        RecyclerView list = activity.findViewById(R.id.replacement_list);
        assertEquals(1, list.getAdapter().getItemCount());
        activity.recreate();
        RecyclerView recreated = activity.findViewById(R.id.replacement_list);
        assertEquals(1, recreated.getAdapter().getItemCount());
    }
}
