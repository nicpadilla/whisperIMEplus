package com.whisperonnx;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.slider.RangeSlider;
import com.whisperonnx.utils.LanguagePairAdapter;
import com.whisperonnx.utils.ThemeUtils;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private int selectedLanguageSlot;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        initializeLanguagePreferences();
        configureImeLanguages();
        configureVoiceInputLanguage();
        configureChineseOptions();
        configureBluetooth();
        configureSliders();
        findViewById(R.id.btn_manage_replacements).setOnClickListener(view ->
                startActivity(new Intent(this, WordReplacementActivity.class)));
        checkPermissions();
    }

    private void initializeLanguagePreferences() {
        String current = preferences.getString("language", "auto");
        if (!preferences.contains("langSelected")) {
            preferences.edit().putInt("langSelected", 1)
                    .putString("language1", current)
                    .putString("language2", "auto").apply();
        }
        selectedLanguageSlot = preferences.getInt("langSelected", 1);
    }

    private void configureImeLanguages() {
        ImageButton btnLang1 = findViewById(R.id.btnLang1);
        ImageButton btnLang2 = findViewById(R.id.btnLang2);
        renderLanguageButtons(btnLang1, btnLang2);
        btnLang1.setOnClickListener(view -> selectSlot(1, btnLang1, btnLang2));
        btnLang2.setOnClickListener(view -> selectSlot(2, btnLang1, btnLang2));

        List<Pair<String, String>> languages = LanguagePairAdapter.getLanguagePairs(this);
        Spinner language1 = findViewById(R.id.spnrLanguage1_ime);
        Spinner language2 = findViewById(R.id.spnrLanguage2_ime);
        LanguagePairAdapter adapter1 = new LanguagePairAdapter(
                this, android.R.layout.simple_spinner_item, languages);
        LanguagePairAdapter adapter2 = new LanguagePairAdapter(
                this, android.R.layout.simple_spinner_item, languages);
        adapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language1.setAdapter(adapter1);
        language2.setAdapter(adapter2);
        language1.setSelection(adapter1.getIndexByCode(
                preferences.getString("language1", "auto")));
        language2.setSelection(adapter2.getIndexByCode(
                preferences.getString("language2", "auto")));
        language1.setOnItemSelectedListener(slotListener(1, "language1", languages));
        language2.setOnItemSelectedListener(slotListener(2, "language2", languages));
    }

    private AdapterView.OnItemSelectedListener slotListener(int slot, String key,
                                                              List<Pair<String, String>> languages) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                String code = languages.get(position).first;
                SharedPreferences.Editor editor = preferences.edit().putString(key, code);
                if (selectedLanguageSlot == slot) editor.putString("language", code);
                editor.apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }

    private void selectSlot(int slot, ImageButton btnLang1, ImageButton btnLang2) {
        selectedLanguageSlot = slot;
        String code = preferences.getString(slot == 1 ? "language1" : "language2", "auto");
        preferences.edit().putInt("langSelected", slot).putString("language", code).apply();
        renderLanguageButtons(btnLang1, btnLang2);
    }

    private void renderLanguageButtons(ImageButton btnLang1, ImageButton btnLang2) {
        btnLang1.setImageResource(selectedLanguageSlot == 1
                ? R.drawable.ic_counter_1_on_36dp : R.drawable.ic_counter_1_off_36dp);
        btnLang2.setImageResource(selectedLanguageSlot == 2
                ? R.drawable.ic_counter_2_on_36dp : R.drawable.ic_counter_2_off_36dp);
    }

    private void configureVoiceInputLanguage() {
        List<Pair<String, String>> languages = LanguagePairAdapter.getLanguagePairs(this);
        Spinner spinner = findViewById(R.id.spnrLanguage);
        LanguagePairAdapter adapter = new LanguagePairAdapter(
                this, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(adapter.getIndexByCode(
                preferences.getString("recognitionServiceLanguage", "auto")));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                preferences.edit().putString("recognitionServiceLanguage",
                        languages.get(position).first).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void configureChineseOptions() {
        CheckBox ime = findViewById(R.id.mode_simple_chinese_ime);
        ime.setChecked(preferences.getBoolean("simpleChinese", false));
        ime.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("simpleChinese", checked).apply());
        CheckBox voice = findViewById(R.id.mode_simple_chinese);
        voice.setChecked(preferences.getBoolean("RecognitionServiceSimpleChinese", false));
        voice.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("RecognitionServiceSimpleChinese", checked).apply());
    }

    private void configureBluetooth() {
        CheckBox bluetooth = findViewById(R.id.mode_bluetooth);
        bluetooth.setChecked(preferences.getBoolean("bluetooth", false));
        bluetooth.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("bluetooth", checked).apply();
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 111);
            }
        });
    }

    private void configureSliders() {
        RangeSlider silence = findViewById(R.id.settings_min_silence);
        silence.setValues((float) preferences.getInt("silenceDurationMs", 800));
        silence.addOnChangeListener((slider, value, fromUser) ->
                preferences.edit().putInt("silenceDurationMs", (int) value).apply());
        RangeSlider duration = findViewById(R.id.settings_max_recording_duration);
        duration.setValues((float) preferences.getInt("maxRecordingSeconds", 120));
        duration.addOnChangeListener((slider, value, fromUser) ->
                preferences.edit().putInt("maxRecordingSeconds", (int) value).apply());
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 0);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
