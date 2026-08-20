package com.whisperonnx;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
import static android.speech.SpeechRecognizer.ERROR_NO_MATCH;
import static android.speech.SpeechRecognizer.ERROR_SERVER;
import static android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.RecorderEvent;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperEvent;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class WhisperRecognitionService extends RecognitionService {
    private static final String TAG = "WhisperRecognitionService";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong nextSessionId = new AtomicLong(1L);
    private SharedPreferences preferences;
    private Recorder recorder;
    private Whisper whisper;
    private Session activeSession;

    @Override public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override protected void onStartListening(Intent intent, Callback callback) {
        disposeActiveSession();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            sendError(callback, ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }
        String language = resolveLanguage(intent);
        Session session = new Session(nextSessionId.getAndIncrement(), callback, language);
        activeSession = session;
        recorder = new Recorder(this);
        whisper = new Whisper(this);
        recorder.setListener(event -> mainHandler.post(() -> handleRecorderEvent(session, event)));
        whisper.setListener(new Whisper.WhisperListener() {
            @Override public void onWhisperEvent(WhisperEvent event) {
                mainHandler.post(() -> handleWhisperEvent(session, event));
            }
            @Override public void onResultReceived(long jobId, WhisperResult result) {
                mainHandler.post(() -> handleResult(session, jobId, result));
            }
        });
        whisper.loadModel();
        try {
            callback.readyForSpeech(new Bundle());
        } catch (RemoteException failure) {
            Log.w(TAG, "readyForSpeech callback failed", failure);
        }
        HapticFeedback.vibrate(this);
        session.recordingId = recorder.start(true);
        if (session.recordingId <= 0L) {
            sendError(callback, ERROR_CLIENT);
            disposeActiveSession();
        }
    }

    @Override protected void onStopListening(Callback callback) {
        Session session = activeSession;
        if (session != null && session.callback == callback && session.recordingId > 0L) {
            recorder.requestStop(session.recordingId);
        }
    }

    @Override protected void onCancel(Callback callback) {
        Session session = activeSession;
        if (session != null && session.callback == callback) session.cancelled = true;
        disposeActiveSession();
    }

    private void handleRecorderEvent(Session session, RecorderEvent event) {
        if (!isCurrent(session) || event.getRequestId() != session.recordingId) return;
        switch (event.getType()) {
            case SPEECH_STARTED:
                if (!session.speechStarted) {
                    session.speechStarted = true;
                    try {
                        session.callback.beginningOfSpeech();
                        session.callback.rmsChanged(10.0f);
                    } catch (RemoteException failure) {
                        Log.w(TAG, "Speech-start callback failed", failure);
                    }
                }
                break;
            case COMPLETED:
                session.recordingId = -1L;
                try {
                    session.callback.rmsChanged(-20.0f);
                    session.callback.endOfSpeech();
                } catch (RemoteException failure) {
                    Log.w(TAG, "Speech-end callback failed", failure);
                }
                session.jobId = whisper.start(event.getRecording(), ACTION_TRANSCRIBE, session.language);
                if (session.jobId <= 0L) {
                    sendError(session.callback, ERROR_SERVER);
                    disposeActiveSession();
                }
                break;
            case CANCELLED:
                session.recordingId = -1L;
                if (!session.cancelled) sendError(session.callback, ERROR_CLIENT);
                disposeActiveSession();
                break;
            case ERROR:
                session.recordingId = -1L;
                int error = event.getErrorCode() == RecorderEvent.ErrorCode.PERMISSION_DENIED
                        ? ERROR_INSUFFICIENT_PERMISSIONS
                        : event.getErrorCode() == RecorderEvent.ErrorCode.NO_AUDIO
                        ? ERROR_SPEECH_TIMEOUT : ERROR_CLIENT;
                sendError(session.callback, error);
                disposeActiveSession();
                break;
            default:
                break;
        }
    }

    private void handleWhisperEvent(Session session, WhisperEvent event) {
        if (!isCurrent(session) || event.getJobId() <= 0L || event.getJobId() != session.jobId) return;
        if (event.getType() == WhisperEvent.Type.ERROR) {
            int error = event.getErrorCode() == WhisperEvent.ErrorCode.SEGMENT_TIMEOUT
                    || event.getErrorCode() == WhisperEvent.ErrorCode.JOB_TIMEOUT
                    ? ERROR_SPEECH_TIMEOUT : ERROR_SERVER;
            sendError(session.callback, error);
            disposeActiveSession();
        } else if (event.getType() == WhisperEvent.Type.CANCELLED) {
            if (!session.cancelled) sendError(session.callback, ERROR_CLIENT);
            disposeActiveSession();
        }
    }

    private void handleResult(Session session, long jobId, WhisperResult result) {
        if (!isCurrent(session) || jobId != session.jobId || session.cancelled) return;
        String text = result.getResult().trim();
        if (text.isEmpty()) {
            sendError(session.callback, ERROR_NO_MATCH);
            disposeActiveSession();
            return;
        }
        if ("zh".equals(result.getLanguage())) {
            text = preferences.getBoolean("RecognitionServiceSimpleChinese", false)
                    ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
        }
        Bundle bundle = new Bundle();
        ArrayList<String> results = new ArrayList<>();
        results.add(text);
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results);
        if (Double.isFinite(result.getConfidence())) {
            bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES,
                    new float[]{(float) result.getConfidence()});
        }
        try {
            session.callback.results(bundle);
        } catch (RemoteException failure) {
            Log.w(TAG, "Recognition result callback failed", failure);
        }
        disposeActiveSession();
    }

    private String resolveLanguage(Intent intent) {
        String configured = preferences.getString("recognitionServiceLanguage", "auto");
        String target = intent == null ? null
                : intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (target == null || target.trim().isEmpty()) return configured;
        return target.split("[-_]")[0].toLowerCase(Locale.ROOT);
    }

    private boolean isCurrent(Session session) {
        return session != null && session == activeSession && !session.cancelled;
    }

    private void sendError(Callback callback, int error) {
        try { callback.error(error); }
        catch (RemoteException failure) { Log.w(TAG, "Recognition error callback failed", failure); }
    }

    private void disposeActiveSession() {
        Session session = activeSession;
        activeSession = null;
        if (session != null) {
            session.cancelled = true;
            if (recorder != null && session.recordingId > 0L) recorder.cancel(session.recordingId);
            if (whisper != null && session.jobId > 0L) whisper.cancel(session.jobId);
        }
        if (recorder != null) recorder.close();
        if (whisper != null) whisper.close();
        recorder = null;
        whisper = null;
    }

    @Override public void onDestroy() {
        disposeActiveSession();
        super.onDestroy();
    }

    private static final class Session {
        final long sessionId;
        final Callback callback;
        final String language;
        long recordingId = -1L;
        long jobId = -1L;
        boolean speechStarted;
        boolean cancelled;

        Session(long sessionId, Callback callback, String language) {
            this.sessionId = sessionId;
            this.callback = callback;
            this.language = language;
        }
    }
}
