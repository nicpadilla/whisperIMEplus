package com.whisperonnx;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.RecordingData;
import com.whisperonnx.asr.RecordingInteractionController;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperEvent;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.RecordingProgressTimer;
import com.whisperonnx.utils.ThemeUtils;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView tvStatus;
    private EditText tvResult;
    private ImageButton btnRecord;
    private LinearLayout layoutTTS;
    private CheckBox append;
    private CheckBox translate;
    private CheckBox modeTTS;
    private ProgressBar processingBar;
    private Recorder recorder;
    private Whisper whisper;
    private RecordingInteractionController interaction;
    private final RecordingProgressTimer progressTimer = new RecordingProgressTimer();
    private SharedPreferences preferences;
    private TextToSpeech tts;
    private long processingStartMs;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        processingBar = findViewById(R.id.processing_bar);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        btnRecord = findViewById(R.id.btnRecord);
        append = findViewById(R.id.mode_append);
        translate = findViewById(R.id.mode_translate);
        layoutTTS = findViewById(R.id.layout_tts);
        modeTTS = findViewById(R.id.mode_tts);
        ImageButton btnInfo = findViewById(R.id.btnInfo);
        FloatingActionButton fabCopy = findViewById(R.id.fabCopy);

        configureTextToSpeech();
        translate.setOnCheckedChangeListener((button, checked) -> {
            layoutTTS.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!checked) modeTTS.setChecked(false);
        });
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/woheller69/whisperIMEplus#Donate"))));
        tvResult.setOnClickListener(view -> tvResult.setCursorVisible(true));
        fabCopy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.model_output), tvResult.getText().toString().trim()));
        });
        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (tvResult.isCursorVisible()) tvResult.setCursorVisible(false); else finish();
            }
        });

        recorder = new Recorder(this);
        whisper = new Whisper(this);
        interaction = new RecordingInteractionController(new InteractionCallbacks());
        recorder.setListener(event -> runOnUiThread(() -> interaction.onRecorderEvent(event)));
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onWhisperEvent(WhisperEvent event) {
                runOnUiThread(() -> {
                    if (event.getType() == WhisperEvent.Type.MODEL_LOADING
                            && interaction.getState() == RecordingInteractionController.State.IDLE) {
                        tvStatus.setText(R.string.processing);
                    } else if (event.getType() == WhisperEvent.Type.MODEL_READY
                            && interaction.getState() == RecordingInteractionController.State.IDLE) {
                        tvStatus.setText("");
                    }
                    interaction.onWhisperEvent(event);
                });
            }

            @Override public void onResultReceived(long jobId, WhisperResult result) {
                runOnUiThread(() -> interaction.onWhisperResult(jobId, result));
            }
        });
        whisper.loadModel();

        btnRecord.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return interaction.onTouchDown(event.getEventTime());
                case MotionEvent.ACTION_UP:
                    return interaction.onTouchUp(event.getEventTime());
                case MotionEvent.ACTION_CANCEL:
                    return interaction.onTouchCancel();
                default:
                    return true;
            }
        });
        btnRecord.setOnClickListener(view ->
                interaction.onAccessibilityClick(SystemClock.uptimeMillis()));

        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) {
            GithubStar.starDialog(this, "https://github.com/woheller69/whisperIMEplus");
        }
        checkPermissions();
        checkInputMethodEnabled();
    }

    private void configureTextToSpeech() {
        modeTTS.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                deinitTTS();
                return;
            }
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.US);
                    if (result == TextToSpeech.LANG_MISSING_DATA
                            || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.tts_language_not_supported,
                                    Toast.LENGTH_SHORT).show();
                            modeTTS.setChecked(false);
                        });
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.tts_initialization_failed, Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    private final class InteractionCallbacks implements RecordingInteractionController.Callbacks {
        @Override public long startRecording(boolean autoStopOnSilence) {
            if (!checkRecordPermission() || whisper.isInProgress()) return -1L;
            if (!append.isChecked()) tvResult.setText("");
            HapticFeedback.vibrate(MainActivity.this);
            progressTimer.start(maxRecordingDurationMs(), processingBar::setProgress);
            return recorder.start(autoStopOnSilence);
        }

        @Override public void requestStopRecording(long requestId) { recorder.requestStop(requestId); }
        @Override public void cancelRecording(long requestId) { recorder.cancel(requestId); }

        @Override public long startTranscription(RecordingData recording) {
            progressTimer.cancel();
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            processingStartMs = System.currentTimeMillis();
            Recognizer.Action action = translate.isChecked() ? ACTION_TRANSLATE : ACTION_TRANSCRIBE;
            String language = preferences.getString("language", "auto");
            return whisper.start(recording, action, language);
        }

        @Override public void cancelTranscription(long jobId) { whisper.cancel(jobId); }

        @Override public void onStateChanged(RecordingInteractionController.State state) {
            renderState(state);
        }

        @Override public void onTranscriptionResult(WhisperResult result) {
            String text = result.getResult();
            if ("zh".equals(result.getLanguage()) && result.getTask() == ACTION_TRANSCRIBE) {
                text = preferences.getBoolean("simpleChinese", false)
                        ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
            }
            tvResult.append(text);
            long elapsed = System.currentTimeMillis() - processingStartMs;
            tvStatus.setText(getString(R.string.processing_done) + elapsed + "\u2009ms\n"
                    + getString(R.string.language) + " "
                    + new Locale(result.getLanguage()).getDisplayLanguage() + " "
                    + (result.getTask() == ACTION_TRANSCRIBE
                    ? getString(R.string.mode_transcription)
                    : getString(R.string.mode_translation)));
            if (modeTTS.isChecked() && tts != null) {
                tts.speak(result.getResult(), TextToSpeech.QUEUE_FLUSH, null, null);
            }
        }

        @Override public void onError(String message) {
            tvStatus.setText(message == null ? getString(R.string.error_no_input) : message);
            Toast.makeText(MainActivity.this, tvStatus.getText(), Toast.LENGTH_SHORT).show();
        }
    }

    private void renderState(RecordingInteractionController.State state) {
        switch (state) {
            case RECORDING_HELD:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                tvStatus.setText(getString(R.string.record_button) + "…");
                processingBar.setIndeterminate(false);
                break;
            case RECORDING_TOGGLED:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                tvStatus.setText(R.string.recording_tap_to_stop);
                processingBar.setIndeterminate(false);
                break;
            case STOPPING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                tvStatus.setText(R.string.finishing_recording);
                break;
            case PROCESSING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                processingBar.setIndeterminate(true);
                tvStatus.setText(R.string.processing);
                break;
            case IDLE:
                progressTimer.cancel();
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                processingBar.setIndeterminate(false);
                processingBar.setProgress(0);
                break;
            case ERROR:
                progressTimer.cancel();
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                processingBar.setIndeterminate(false);
                processingBar.setProgress(0);
                break;
            default:
                break;
        }
    }

    private long maxRecordingDurationMs() {
        return Math.max(1, preferences.getInt("maxRecordingSeconds", 120)) * 1000L;
    }

    private boolean checkRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return true;
        Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
        checkPermissions();
        return false;
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && preferences.getBoolean("bluetooth", false)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 0);
    }

    private void checkInputMethodEnabled() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) return;
        String id = getPackageName() + "/" + WhisperInputMethodService.class.getName();
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (id.equals(info.getId())) return;
        }
        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onPause() {
        if (interaction != null) interaction.cancelActiveWork();
        super.onPause();
    }

    @Override protected void onDestroy() {
        progressTimer.cancel();
        if (interaction != null) interaction.dispose();
        if (recorder != null) recorder.close();
        if (whisper != null) whisper.close();
        deinitTTS();
        super.onDestroy();
    }

    private void deinitTTS() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
