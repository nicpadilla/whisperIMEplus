package com.whisperonnx;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.RecordingData;
import com.whisperonnx.asr.RecordingInteractionController;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperEvent;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.RecordingProgressTimer;

import java.util.ArrayList;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final String TAG = "WhisperRecognizeActivity";

    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ProgressBar processingBar;
    private SharedPreferences preferences;
    private Recorder recorder;
    private Whisper whisper;
    private RecordingInteractionController interaction;
    private final RecordingProgressTimer progressTimer = new RecordingProgressTimer();
    private boolean modeAuto;
    private String languageCode;

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        languageCode = resolveLanguage(getIntent());
        setContentView(R.layout.activity_recognize);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;
        getWindow().setAttributes(params);

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        btnModeAuto = findViewById(R.id.btnModeAuto);
        processingBar = findViewById(R.id.processing_bar);
        modeAuto = preferences.getBoolean("imeModeAuto", false);
        btnModeAuto.setImageResource(modeAuto
                ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        btnRecord.setVisibility(modeAuto ? View.GONE : View.VISIBLE);

        recorder = new Recorder(this);
        whisper = new Whisper(this);
        interaction = new RecordingInteractionController(new InteractionCallbacks());
        recorder.setListener(event -> runOnUiThread(() -> interaction.onRecorderEvent(event)));
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onWhisperEvent(WhisperEvent event) {
                runOnUiThread(() -> interaction.onWhisperEvent(event));
            }
            @Override public void onResultReceived(long jobId, WhisperResult result) {
                runOnUiThread(() -> interaction.onWhisperResult(jobId, result));
            }
        });
        whisper.loadModel();

        btnRecord.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: return interaction.onTouchDown(event.getEventTime());
                case MotionEvent.ACTION_UP: return interaction.onTouchUp(event.getEventTime());
                case MotionEvent.ACTION_CANCEL: return interaction.onTouchCancel();
                default: return true;
            }
        });
        btnRecord.setOnClickListener(target ->
                interaction.onAccessibilityClick(SystemClock.uptimeMillis()));
        btnCancel.setOnClickListener(target -> {
            interaction.cancelActiveWork();
            setResult(RESULT_CANCELED);
            finish();
        });
        btnModeAuto.setOnClickListener(target -> {
            modeAuto = !modeAuto;
            preferences.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setImageResource(modeAuto
                    ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            interaction.cancelActiveWork();
            setResult(RESULT_CANCELED);
            finish();
        });

        if (modeAuto) interaction.startAutomaticRecording();
    }

    private String resolveLanguage(Intent intent) {
        String configured = preferences.getString("language", "auto");
        String target = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (target == null || target.trim().isEmpty()) return configured;
        return target.split("[-_]")[0].toLowerCase(java.util.Locale.ROOT);
    }

    private final class InteractionCallbacks implements RecordingInteractionController.Callbacks {
        @Override public long startRecording(boolean autoStopOnSilence) {
            if (!checkRecordPermission() || whisper.isInProgress()) return -1L;
            HapticFeedback.vibrate(WhisperRecognizeActivity.this);
            progressTimer.start(maxRecordingDurationMs(), processingBar::setProgress);
            return recorder.start(autoStopOnSilence);
        }
        @Override public void requestStopRecording(long requestId) { recorder.requestStop(requestId); }
        @Override public void cancelRecording(long requestId) { recorder.cancel(requestId); }
        @Override public long startTranscription(RecordingData recording) {
            progressTimer.cancel();
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            return whisper.start(recording, ACTION_TRANSCRIBE, languageCode);
        }
        @Override public void cancelTranscription(long jobId) { whisper.cancel(jobId); }
        @Override public void onStateChanged(RecordingInteractionController.State state) { renderState(state); }
        @Override public void onTranscriptionResult(WhisperResult result) {
            String text = result.getResult();
            if ("zh".equals(result.getLanguage())) {
                text = preferences.getBoolean("simpleChinese", false)
                        ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
            }
            if (!text.trim().isEmpty()) sendResult(text.trim(), result.getConfidence());
        }
        @Override public void onError(String message) {
            Toast.makeText(WhisperRecognizeActivity.this,
                    message == null ? getString(R.string.error_no_input) : message,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void renderState(RecordingInteractionController.State state) {
        switch (state) {
            case RECORDING_HELD:
            case RECORDING_TOGGLED:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                processingBar.setIndeterminate(false);
                break;
            case STOPPING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                break;
            case PROCESSING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                processingBar.setIndeterminate(true);
                break;
            case IDLE:
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

    private boolean checkRecordPermission() {
        boolean granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) Toast.makeText(this, R.string.need_record_audio_permission,
                Toast.LENGTH_SHORT).show();
        return granted;
    }

    private long maxRecordingDurationMs() {
        return Math.max(1, preferences.getInt("maxRecordingSeconds", 120)) * 1000L;
    }

    private void sendResult(String result, double confidence) {
        Intent resultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        float score = Double.isFinite(confidence) ? (float) confidence : 1.0f;
        resultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        resultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{score});
        setResult(RESULT_OK, resultIntent);

        PendingIntent pendingIntent = null;
        try {
            pendingIntent = getIntent().getParcelableExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Failed to read result PendingIntent", failure);
        }
        if (pendingIntent != null) {
            Intent pendingResult = new Intent();
            pendingResult.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
            pendingResult.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{score});
            pendingResult.putExtra(SearchManager.QUERY, result);
            Bundle bundle = getIntent().getBundleExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
            if (bundle != null) {
                Bundle copied = new Bundle(bundle);
                copied.putStringArrayList(RecognizerIntent.EXTRA_RESULTS, results);
                copied.putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{score});
                copied.putString(SearchManager.QUERY, result);
                pendingResult.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, copied);
            }
            try {
                pendingIntent.send(this, RESULT_OK, pendingResult);
            } catch (PendingIntent.CanceledException failure) {
                Log.e(TAG, "Result PendingIntent was cancelled", failure);
            }
        } else if (getCallingActivity() == null) {
            if (RecognizerIntent.ACTION_WEB_SEARCH.equals(getIntent().getAction())) {
                Intent search = new Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, result)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(search); }
                catch (RuntimeException failure) { Log.e(TAG, "Web search failed", failure); }
            } else {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Whisper result", result));
                    Toast.makeText(this, R.string.copy_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }

    @Override protected void onDestroy() {
        progressTimer.cancel();
        if (interaction != null) interaction.dispose();
        if (recorder != null) recorder.close();
        if (whisper != null) whisper.close();
        super.onDestroy();
    }
}
