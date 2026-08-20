package com.whisperonnx;

import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RecordingProgressTimer progressTimer = new RecordingProgressTimer();

    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnLang1;
    private ImageButton btnLang2;
    private TextView tvStatus;
    private ProgressBar processingBar;
    private RelativeLayout layoutButtons;
    private SharedPreferences preferences;
    private Recorder recorder;
    private Whisper whisper;
    private RecordingInteractionController interaction;
    private boolean modeAuto;
    private static boolean translate;

    @Override public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public View onCreateInputView() {
        disposeSession();
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) target.getLayoutParams();
            params.leftMargin = bars.left;
            params.rightMargin = bars.right;
            params.bottomMargin = bars.bottom;
            target.setLayoutParams(params);
            return WindowInsetsCompat.CONSUMED;
        });

        bindViews(view);
        initializeLanguagePreferences();
        renderLanguageButtons();
        modeAuto = preferences.getBoolean("imeModeAuto", false);
        btnModeAuto.setImageResource(modeAuto
                ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        btnTranslate.setImageResource(translate
                ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);

        recorder = new Recorder(this);
        whisper = new Whisper(this);
        interaction = new RecordingInteractionController(new InteractionCallbacks());
        recorder.setListener(event -> handler.post(() -> interaction.onRecorderEvent(event)));
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onWhisperEvent(WhisperEvent event) {
                handler.post(() -> interaction.onWhisperEvent(event));
            }
            @Override public void onResultReceived(long jobId, WhisperResult result) {
                handler.post(() -> interaction.onWhisperResult(jobId, result));
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
        configureDeleteButton();
        configureActionButtons();

        if (modeAuto) interaction.startAutomaticRecording();
        return view;
    }

    private void bindViews(View view) {
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        btnLang1 = view.findViewById(R.id.btnLang1);
        btnLang2 = view.findViewById(R.id.btnLang2);
        tvStatus = view.findViewById(R.id.tv_status);
        processingBar = view.findViewById(R.id.processing_bar);
        layoutButtons = view.findViewById(R.id.layout_buttons);
    }

    private void initializeLanguagePreferences() {
        String current = preferences.getString("language", "auto");
        if (!preferences.contains("langSelected")) {
            preferences.edit().putInt("langSelected", 1)
                    .putString("language1", current)
                    .putString("language2", "auto").apply();
        }
    }

    private void renderLanguageButtons() {
        int selected = preferences.getInt("langSelected", 1);
        btnLang1.setImageResource(selected == 1
                ? R.drawable.ic_counter_1_on_36dp : R.drawable.ic_counter_1_off_36dp);
        btnLang2.setImageResource(selected == 2
                ? R.drawable.ic_counter_2_on_36dp : R.drawable.ic_counter_2_off_36dp);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureDeleteButton() {
        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initial;
            private Runnable repeat;

            @Override public boolean onTouch(View target, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sendDelete();
                    initial = () -> {
                        sendDelete();
                        repeat = new Runnable() {
                            @Override public void run() {
                                sendDelete();
                                handler.postDelayed(this, 100L);
                            }
                        };
                        handler.postDelayed(repeat, 100L);
                    };
                    handler.postDelayed(initial, 500L);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (initial != null) handler.removeCallbacks(initial);
                    if (repeat != null) handler.removeCallbacks(repeat);
                    initial = null;
                    repeat = null;
                }
                return true;
            }
        });
    }

    private void sendDelete() {
        if (getCurrentInputConnection() != null) {
            getCurrentInputConnection().sendKeyEvent(
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        }
    }

    private void configureActionButtons() {
        btnKeyboard.setOnClickListener(target -> {
            if (interaction != null) interaction.cancelActiveWork();
            switchToPreviousInputMethod();
        });
        btnTranslate.setOnClickListener(target -> {
            translate = !translate;
            btnTranslate.setImageResource(translate
                    ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });
        btnEnter.setOnClickListener(target -> {
            if (getCurrentInputConnection() != null) {
                getCurrentInputConnection().sendKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            }
        });
        btnModeAuto.setOnClickListener(target -> {
            modeAuto = !modeAuto;
            preferences.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setImageResource(modeAuto
                    ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            if (interaction != null) interaction.cancelActiveWork();
            switchToPreviousInputMethod();
        });
        btnLang1.setOnClickListener(target -> selectLanguage(1));
        btnLang2.setOnClickListener(target -> selectLanguage(2));
    }

    private void selectLanguage(int slot) {
        String language = preferences.getString(slot == 1 ? "language1" : "language2", "auto");
        preferences.edit().putInt("langSelected", slot).putString("language", language).apply();
        renderLanguageButtons();
    }

    private final class InteractionCallbacks implements RecordingInteractionController.Callbacks {
        @Override public long startRecording(boolean autoStopOnSilence) {
            if (!checkRecordPermission() || whisper.isInProgress()) return -1L;
            HapticFeedback.vibrate(WhisperInputMethodService.this);
            progressTimer.start(maxRecordingDurationMs(), processingBar::setProgress);
            return recorder.start(autoStopOnSilence);
        }
        @Override public void requestStopRecording(long requestId) { recorder.requestStop(requestId); }
        @Override public void cancelRecording(long requestId) { recorder.cancel(requestId); }
        @Override public long startTranscription(RecordingData recording) {
            progressTimer.cancel();
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            String language = preferences.getString("language", "auto");
            return whisper.start(recording, translate ? ACTION_TRANSLATE : ACTION_TRANSCRIBE, language);
        }
        @Override public void cancelTranscription(long jobId) { whisper.cancel(jobId); }
        @Override public void onStateChanged(RecordingInteractionController.State state) { renderState(state); }
        @Override public void onTranscriptionResult(WhisperResult result) {
            String text = result.getResult();
            if ("zh".equals(result.getLanguage())) {
                text = preferences.getBoolean("simpleChinese", false)
                        ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
            }
            boolean committed = getCurrentInputConnection() != null && !text.trim().isEmpty()
                    && getCurrentInputConnection().commitText(text.trim() + " ", 1);
            if (modeAuto && committed) handler.postDelayed(
                    WhisperInputMethodService.this::switchToPreviousInputMethod, 100L);
        }
        @Override public void onError(String message) {
            tvStatus.setText(message == null ? getString(R.string.error_no_input) : message);
            tvStatus.setVisibility(View.VISIBLE);
        }
    }

    private void renderState(RecordingInteractionController.State state) {
        if (btnRecord == null) return;
        switch (state) {
            case RECORDING_HELD:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                tvStatus.setVisibility(View.GONE);
                processingBar.setIndeterminate(false);
                break;
            case RECORDING_TOGGLED:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                tvStatus.setText(R.string.recording_tap_to_stop);
                tvStatus.setVisibility(View.VISIBLE);
                processingBar.setIndeterminate(false);
                break;
            case STOPPING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                tvStatus.setText(R.string.finishing_recording);
                tvStatus.setVisibility(View.VISIBLE);
                break;
            case PROCESSING:
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                tvStatus.setText(R.string.processing);
                tvStatus.setVisibility(View.VISIBLE);
                processingBar.setIndeterminate(true);
                break;
            case IDLE:
                progressTimer.cancel();
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
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

    private boolean checkRecordPermission() {
        boolean granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted && tvStatus != null) {
            tvStatus.setText(R.string.need_record_audio_permission);
            tvStatus.setVisibility(View.VISIBLE);
        }
        return granted;
    }

    private long maxRecordingDurationMs() {
        return Math.max(1, preferences.getInt("maxRecordingSeconds", 120)) * 1000L;
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (attribute.inputType == EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Cancelling inactive input connection for " + attribute.packageName);
            disposeSession();
        }
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        if (interaction != null) interaction.cancelActiveWork();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onDestroy() {
        disposeSession();
        super.onDestroy();
    }

    private void disposeSession() {
        progressTimer.cancel();
        if (interaction != null) interaction.dispose();
        if (recorder != null) recorder.close();
        if (whisper != null) whisper.close();
        interaction = null;
        recorder = null;
        whisper = null;
    }
}
