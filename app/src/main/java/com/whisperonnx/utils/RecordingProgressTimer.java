package com.whisperonnx.utils;

import android.os.CountDownTimer;

/** Small lifecycle-safe wrapper around a recording countdown/progress callback. */
public final class RecordingProgressTimer {
    public interface Listener {
        void onProgress(int percentRemaining);
    }

    private CountDownTimer timer;

    public void start(long durationMs, Listener listener) {
        cancel();
        long safeDuration = Math.max(1L, durationMs);
        listener.onProgress(100);
        timer = new CountDownTimer(safeDuration, 250L) {
            @Override public void onTick(long millisUntilFinished) {
                listener.onProgress((int) Math.max(0L,
                        Math.min(100L, millisUntilFinished * 100L / safeDuration)));
            }
            @Override public void onFinish() { listener.onProgress(0); }
        };
        timer.start();
    }

    public void cancel() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }
}
