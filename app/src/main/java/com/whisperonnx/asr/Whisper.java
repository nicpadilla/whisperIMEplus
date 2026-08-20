package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.whisperonnx.SetupActivity;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;

    // Multi-segment synchronization
    private volatile CountDownLatch segmentLatch;
    private volatile WhisperResult segmentResult;

    public Whisper(Context context) {
        mContext = context;

        //check if model is installed
        File sdcardDataFolder = mContext.getExternalFilesDir(null);

        if (sdcardDataFolder != null && !sdcardDataFolder.exists() && !sdcardDataFolder.mkdirs()) {
            Log.e(TAG, "Failed to make directory: " + sdcardDataFolder);
            return;
        }

        File[] files = sdcardDataFolder.listFiles();

        int fileCount = 0;
        for (File file : files) {
            if (file.isFile()) {
                fileCount++;
            }
        }
        if (fileCount != 6) { //install model
            Intent intent = new Intent(mContext, SetupActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } else { // Start thread for RecordBuffer transcription
            Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
            threadProcessRecordBuffer.start();
        }

    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel() {
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
            }
        });


        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text, languageCode, mAction);

                if (segmentLatch != null) {
                    // Multi-segment mode: store result and signal
                    segmentResult = whisperResult;
                    segmentLatch.countDown();
                } else {
                    // Single-segment mode: deliver immediately
                    sendResult(whisperResult);
                    long timeTaken = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                    sendUpdate(MSG_PROCESSING_DONE);
                }
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "ERROR during recognition");
                if (segmentLatch != null) {
                    segmentResult = null;
                    segmentLatch.countDown();
                }
            }
        });
    }

    public void unloadModel() {
        if (recognizer != null) {
            recognizer.destroy();
        }
    }

    public void setAction(Recognizer.Action action) {
        this.mAction = action;
    }

    public void setLanguage(String language){
        this.mLangCode = language;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);

                int segmentCount = RecordBuffer.getSegmentCount();
                if (segmentCount <= 1) {
                    // Single segment: use existing behavior (backward compatible)
                    recognizer.recognize(RecordBuffer.getSamples(), 1, mLangCode, mAction);
                } else {
                    // Multi-segment: process each segment sequentially
                    processMultipleSegments(segmentCount);
                }
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void processMultipleSegments(int segmentCount) {
        StringBuilder accumulatedText = new StringBuilder();
        String detectedLanguage = mLangCode;

        for (int i = 0; i < segmentCount; i++) {
            sendUpdate("Processing segment " + (i + 1) + " of " + segmentCount + "...");

            float[] segmentSamples = RecordBuffer.getSegmentSamples(i);
            if (segmentSamples == null || segmentSamples.length == 0) continue;

            // Set up latch for this segment
            segmentLatch = new CountDownLatch(1);
            segmentResult = null;

            recognizer.recognize(segmentSamples, 1, mLangCode, mAction);

            try {
                segmentLatch.await(); // Wait for callback
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (segmentResult != null) {
                String text = segmentResult.getResult().trim();
                if (!text.isEmpty() && !text.equals(Recognizer.UNDEFINED_TEXT)) {
                    if (accumulatedText.length() > 0) {
                        accumulatedText.append(" ");
                    }
                    accumulatedText.append(text);
                }
                detectedLanguage = segmentResult.getLanguage();
            }
        }

        // Clean up
        segmentLatch = null;
        segmentResult = null;

        // Send accumulated result
        WhisperResult finalResult = new WhisperResult(accumulatedText.toString(), detectedLanguage, mAction);
        sendResult(finalResult);

        long timeTaken = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Time Taken for multi-segment transcription (" + segmentCount + " segments): " + timeTaken + "ms");
        sendUpdate(MSG_PROCESSING_DONE);
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            List<WordReplacements.Entry> replacements = WordReplacements.load(
                    PreferenceManager.getDefaultSharedPreferences(mContext));
            String replaced = WordReplacements.applyReplacements(whisperResult.getResult(), replacements);
            WhisperResult finalResult = new WhisperResult(replaced, whisperResult.getLanguage(), whisperResult.getTask());
            mUpdateListener.onResultReceived(finalResult);
        }
    }

}
