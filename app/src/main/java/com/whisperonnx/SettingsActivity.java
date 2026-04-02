package com.whisperonnx;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.slider.RangeSlider;
import com.whisperonnx.asr.WordReplacements;
import com.whisperonnx.utils.LanguagePairAdapter;
import com.whisperonnx.utils.ThemeUtils;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    private SharedPreferences sp = null;
    private Spinner spinnerLanguage;
    private Spinner spinnerLanguage1IME;
    private Spinner spinnerLanguage2IME;
    private CheckBox modeSimpleChinese;
    private CheckBox modeSimpleChineseIME;
    private String langCodeIME = "";
    private RangeSlider minSilence;
    private RangeSlider maxRecordingDuration;
    private CheckBox useBluetoothMic;
    private Button btnAddReplacement;
    private List<WordReplacements.Entry> wordReplacementEntries;
    private int langSelected;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        langCodeIME = sp.getString("language", "auto");

        if (!sp.contains("langSelected")){
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected",1);
            editor.putString("language1",langCodeIME);
            editor.putString("language2","auto");
            editor.commit();
        }

        ImageButton btnLang1 = findViewById(R.id.btnLang1);
        ImageButton btnLang2 = findViewById(R.id.btnLang2);

        langSelected = sp.getInt("langSelected", 1);
        if (langSelected == 1) {
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        } else {
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        }

        btnLang1.setOnClickListener(v -> {
            String lang = sp.getString("language1", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 1);
            editor.putString("language", lang);
            editor.apply();
            langSelected = 1;
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        });

        btnLang2.setOnClickListener(v -> {
            String lang = sp.getString("language2", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 2);
            editor.putString("language", lang);
            editor.apply();
            langSelected = 2;
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        });

        spinnerLanguage1IME = findViewById(R.id.spnrLanguage1_ime);
        spinnerLanguage2IME = findViewById(R.id.spnrLanguage2_ime);

        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);

        LanguagePairAdapter languagePairAdapter1IME = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        LanguagePairAdapter languagePairAdapter2IME = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter1IME.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage1IME.setAdapter(languagePairAdapter1IME);
        spinnerLanguage2IME.setAdapter(languagePairAdapter2IME);
        String langCode1IME = sp.getString("language1", "auto");
        String langCode2IME = sp.getString("language2", "auto");

        spinnerLanguage1IME.setSelection(languagePairAdapter1IME.getIndexByCode(langCode1IME));
        spinnerLanguage2IME.setSelection(languagePairAdapter2IME.getIndexByCode(langCode2IME));

        spinnerLanguage1IME.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language1",languagePairs.get(i).first);
                if (langSelected == 1) {
                    langCodeIME = languagePairs.get(i).first;
                    editor.putString("language",languagePairs.get(i).first);
                }
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        spinnerLanguage2IME.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language2",languagePairs.get(i).first);
                if (langSelected == 2) {
                    langCodeIME = languagePairs.get(i).first;
                    editor.putString("language",languagePairs.get(i).first);
                }
                editor.apply();

            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        modeSimpleChineseIME = findViewById(R.id.mode_simple_chinese_ime);
        modeSimpleChineseIME.setChecked(sp.getBoolean("simpleChinese",false));  //default to traditional Chinese
        modeSimpleChineseIME.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("simpleChinese", isChecked);
            editor.apply();
        });

        spinnerLanguage = findViewById(R.id.spnrLanguage);

        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);

        String langCode = sp.getString("recognitionServiceLanguage", "auto");
        spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("recognitionServiceLanguage", languagePairs.get(i).first);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        modeSimpleChinese = findViewById(R.id.mode_simple_chinese);
        modeSimpleChinese.setChecked(sp.getBoolean("RecognitionServiceSimpleChinese",false));  //default to traditional Chinese
        modeSimpleChinese.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("RecognitionServiceSimpleChinese", isChecked);
            editor.apply();
        });

        minSilence = findViewById(R.id.settings_min_silence);
        float silence = sp.getInt("silenceDurationMs", 800);
        minSilence.setValues(silence);
        minSilence.addOnChangeListener(new RangeSlider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt("silenceDurationMs", (int) value);
                editor.apply();
            }
        });

        maxRecordingDuration = findViewById(R.id.settings_max_recording_duration);
        float maxDuration = sp.getInt("maxRecordingSeconds", 120);
        maxRecordingDuration.setValues(maxDuration);
        maxRecordingDuration.addOnChangeListener(new RangeSlider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt("maxRecordingSeconds", (int) value);
                editor.apply();
            }
        });

        useBluetoothMic = findViewById(R.id.use_bluetooth_mic);
        useBluetoothMic.setChecked(sp.getBoolean("useBluetoothMic", false));
        useBluetoothMic.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("useBluetoothMic", isChecked);
            editor.apply();
        });

        // Word replacements
        LinearLayout listWordReplacements = findViewById(R.id.list_word_replacements);
        btnAddReplacement = findViewById(R.id.btn_add_replacement);
        wordReplacementEntries = WordReplacements.load(sp);
        refreshReplacementList(listWordReplacements);

        btnAddReplacement.setOnClickListener(v -> {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_replacement, null);
            EditText etFrom = dialogView.findViewById(R.id.et_from);
            EditText etTo = dialogView.findViewById(R.id.et_to);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.replacement_add_title)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String from = etFrom.getText().toString().trim();
                        String to = etTo.getText().toString().trim();
                        if (!from.isEmpty() && !to.isEmpty()) {
                            wordReplacementEntries.add(new WordReplacements.Entry(from, to));
                            WordReplacements.save(sp, wordReplacementEntries);
                            refreshReplacementList(listWordReplacements);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        checkPermissions();

    }

    private void refreshReplacementList(LinearLayout container) {
        container.removeAllViews();
        for (int i = 0; i < wordReplacementEntries.size(); i++) {
            final int index = i;
            WordReplacements.Entry entry = wordReplacementEntries.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_word_replacement, container, false);
            TextView tvEntry = row.findViewById(R.id.tv_replacement_entry);
            ImageButton btnDelete = row.findViewById(R.id.btn_delete_replacement);
            tvEntry.setText(entry.from + "  \u2192  " + entry.to);
            btnDelete.setOnClickListener(v -> {
                wordReplacementEntries.remove(index);
                WordReplacements.save(sp, wordReplacementEntries);
                refreshReplacementList(container);
            });
            container.addView(row);
        }
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) { //handle "back click" on action bar
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}