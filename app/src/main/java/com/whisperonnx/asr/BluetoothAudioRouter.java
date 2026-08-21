package com.whisperonnx.asr;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Selects and owns the Bluetooth communication route used by one recording request.
 *
 * <p>API 31+ uses the communication-device APIs. API 28-30 uses the legacy SCO
 * connection broadcast. Setup is bounded and falls back to the default microphone when
 * routing cannot be confirmed.</p>
 */
final class BluetoothAudioRouter implements AutoCloseable {
    interface ListenerRegistration extends AutoCloseable {
        @Override void close();
    }

    interface CommunicationDeviceListener {
        void onDeviceChanged(int deviceId);
    }

    interface LegacyScoListener {
        void onScoStateChanged(boolean connected);
    }

    interface Platform extends AutoCloseable {
        int apiLevel();
        boolean hasBluetoothConnectPermission();
        int getAudioMode();
        void setAudioMode(int mode);

        List<RouteDevice> getAvailableCommunicationDevices();
        int getCurrentCommunicationDeviceId();
        boolean setCommunicationDevice(int deviceId);
        ListenerRegistration addCommunicationDeviceListener(CommunicationDeviceListener listener);
        void clearCommunicationDevice();

        boolean isLegacyScoConnected();
        ListenerRegistration addLegacyScoListener(LegacyScoListener listener);
        void startLegacySco();
        void stopLegacySco();

        @Override void close();
    }

    interface TimeSource {
        long nowMs();
        void waitOn(Object monitor, long millis) throws InterruptedException;
    }

    static final class RouteDevice {
        final int id;
        final int type;

        RouteDevice(int id, int type) {
            this.id = id;
            this.type = type;
        }
    }

    static final class RouteSession implements AutoCloseable {
        private final Platform platform;
        private final boolean modern;
        private final int targetDeviceId;
        private final AtomicInteger currentDeviceId;
        private final AtomicBoolean legacyConnected;
        private ListenerRegistration registration;
        private final int previousAudioMode;
        private final String requestedRoute;
        private String actualRoute;
        private boolean bluetoothOwned;
        private boolean closed;

        private RouteSession(Platform platform, boolean modern, int targetDeviceId,
                             AtomicInteger currentDeviceId, AtomicBoolean legacyConnected,
                             ListenerRegistration registration, int previousAudioMode,
                             String requestedRoute, String actualRoute, boolean bluetoothOwned) {
            this.platform = platform;
            this.modern = modern;
            this.targetDeviceId = targetDeviceId;
            this.currentDeviceId = currentDeviceId;
            this.legacyConnected = legacyConnected;
            this.registration = registration;
            this.previousAudioMode = previousAudioMode;
            this.requestedRoute = requestedRoute;
            this.actualRoute = actualRoute;
            this.bluetoothOwned = bluetoothOwned;
        }

        static RouteSession defaultRoute(Platform platform, String requestedRoute,
                                         String actualRoute) {
            return new RouteSession(platform, false, -1, null, null, null,
                    platform.getAudioMode(), requestedRoute, actualRoute, false);
        }

        String getRequestedRoute() { return requestedRoute; }
        String getActualRoute() { return actualRoute; }
        boolean isBluetoothActive() { return bluetoothOwned && !closed; }

        /**
         * Applies the default-route fallback after an asynchronous disconnect.
         *
         * @return true when the observable route changed during this call.
         */
        boolean refresh() {
            if (closed || !bluetoothOwned) return false;
            boolean connected;
            if (modern) {
                int observed = platform.getCurrentCommunicationDeviceId();
                if (currentDeviceId != null) currentDeviceId.set(observed);
                connected = observed == targetDeviceId;
            } else {
                connected = legacyConnected != null && legacyConnected.get();
            }
            if (connected) return false;
            fallbackToDefault("default-after-bluetooth-disconnect");
            return true;
        }

        private void fallbackToDefault(String reason) {
            if (!bluetoothOwned) {
                actualRoute = reason;
                return;
            }
            bluetoothOwned = false;
            closeRegistration();
            try {
                if (modern) platform.clearCommunicationDevice();
                else platform.stopLegacySco();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Bluetooth fallback cleanup failed", failure);
            }
            restoreAudioMode();
            actualRoute = reason;
        }

        private void closeRegistration() {
            ListenerRegistration current = registration;
            registration = null;
            if (current != null) {
                try { current.close(); }
                catch (RuntimeException failure) {
                    Log.w(TAG, "Bluetooth route listener cleanup failed", failure);
                }
            }
        }

        private void restoreAudioMode() {
            try {
                if (platform.getAudioMode() == AudioManager.MODE_IN_COMMUNICATION) {
                    platform.setAudioMode(previousAudioMode);
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Audio mode restoration failed", failure);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            closeRegistration();
            if (bluetoothOwned) {
                bluetoothOwned = false;
                try {
                    if (modern) platform.clearCommunicationDevice();
                    else platform.stopLegacySco();
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Bluetooth route teardown failed", failure);
                }
                restoreAudioMode();
            }
        }
    }

    private static final String TAG = "BluetoothAudioRouter";
    static final long DEFAULT_ROUTE_TIMEOUT_MS = 2_000L;
    private static final long POLL_INTERVAL_MS = 50L;

    private final Platform platform;
    private final TimeSource timeSource;
    private volatile boolean closed;

    BluetoothAudioRouter(Context context) {
        this(new AndroidPlatform(context.getApplicationContext()), new SystemTimeSource());
    }

    BluetoothAudioRouter(Platform platform, TimeSource timeSource) {
        if (platform == null || timeSource == null) {
            throw new IllegalArgumentException("platform and timeSource are required");
        }
        this.platform = platform;
        this.timeSource = timeSource;
    }

    RouteSession open(boolean bluetoothRequested, long timeoutMs, BooleanSupplier stopRequested) {
        if (closed) return RouteSession.defaultRoute(platform,
                bluetoothRequested ? "bluetooth" : "default", "default-router-closed");
        if (!bluetoothRequested) {
            return RouteSession.defaultRoute(platform, "default", "default");
        }
        if (stopRequested != null && stopRequested.getAsBoolean()) {
            return RouteSession.defaultRoute(platform, "bluetooth", "default-route-cancelled");
        }
        try {
            return platform.apiLevel() >= Build.VERSION_CODES.S
                    ? openModern(timeoutMs, stopRequested)
                    : openLegacy(timeoutMs, stopRequested);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Bluetooth route setup failed; using default microphone", failure);
            return RouteSession.defaultRoute(platform, "bluetooth",
                    "default-bluetooth-error");
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private RouteSession openModern(long timeoutMs, BooleanSupplier stopRequested) {
        if (!platform.hasBluetoothConnectPermission()) {
            return RouteSession.defaultRoute(platform, "bluetooth",
                    "default-bluetooth-permission-denied");
        }
        RouteDevice target = selectBluetoothDevice(platform.getAvailableCommunicationDevices());
        if (target == null) {
            return RouteSession.defaultRoute(platform, "bluetooth",
                    "default-no-bluetooth-device");
        }

        int previousMode = platform.getAudioMode();
        AtomicInteger currentDevice = new AtomicInteger(platform.getCurrentCommunicationDeviceId());
        Object monitor = new Object();
        ListenerRegistration registration = platform.addCommunicationDeviceListener(deviceId -> {
            currentDevice.set(deviceId);
            synchronized (monitor) { monitor.notifyAll(); }
        });
        boolean accepted = false;
        try {
            platform.setAudioMode(AudioManager.MODE_IN_COMMUNICATION);
            accepted = platform.setCommunicationDevice(target.id);
            if (!accepted) {
                cleanupFailedModern(registration, previousMode);
                return RouteSession.defaultRoute(platform, "bluetooth",
                        "default-bluetooth-request-rejected");
            }
            boolean connected = await(monitor,
                    () -> currentDevice.get() == target.id
                            || platform.getCurrentCommunicationDeviceId() == target.id,
                    timeoutMs, stopRequested);
            if (!connected) {
                cleanupFailedModern(registration, previousMode);
                String reason = stopRequested != null && stopRequested.getAsBoolean()
                        ? "default-route-cancelled" : "default-bluetooth-timeout";
                return RouteSession.defaultRoute(platform, "bluetooth", reason);
            }
            currentDevice.set(target.id);
            return new RouteSession(platform, true, target.id, currentDevice, null,
                    registration, previousMode, "bluetooth", routeLabel(target), true);
        } catch (RuntimeException failure) {
            if (accepted) {
                try { platform.clearCommunicationDevice(); }
                catch (RuntimeException ignored) { }
            }
            safeClose(registration);
            restoreMode(previousMode);
            throw failure;
        }
    }

    private RouteSession openLegacy(long timeoutMs, BooleanSupplier stopRequested) {
        int previousMode = platform.getAudioMode();
        AtomicBoolean connected = new AtomicBoolean(platform.isLegacyScoConnected());
        Object monitor = new Object();
        ListenerRegistration registration = platform.addLegacyScoListener(isConnected -> {
            connected.set(isConnected);
            synchronized (monitor) { monitor.notifyAll(); }
        });
        try {
            platform.setAudioMode(AudioManager.MODE_IN_COMMUNICATION);
            platform.startLegacySco();
            boolean routeReady = await(monitor,
                    () -> connected.get() || platform.isLegacyScoConnected(),
                    timeoutMs, stopRequested);
            if (!routeReady) {
                cleanupFailedLegacy(registration, previousMode);
                String reason = stopRequested != null && stopRequested.getAsBoolean()
                        ? "default-route-cancelled" : "default-bluetooth-timeout";
                return RouteSession.defaultRoute(platform, "bluetooth", reason);
            }
            connected.set(true);
            return new RouteSession(platform, false, -1, null, connected,
                    registration, previousMode, "bluetooth", "bluetooth-sco", true);
        } catch (RuntimeException failure) {
            cleanupFailedLegacy(registration, previousMode);
            throw failure;
        }
    }

    private boolean await(Object monitor, BooleanSupplier condition, long timeoutMs,
                          BooleanSupplier stopRequested) {
        long boundedTimeout = Math.max(1L, timeoutMs);
        long deadline = safeAdd(timeSource.nowMs(), boundedTimeout);
        synchronized (monitor) {
            while (!condition.getAsBoolean()) {
                if (stopRequested != null && stopRequested.getAsBoolean()) return false;
                long remaining = deadline - timeSource.nowMs();
                if (remaining <= 0L) return false;
                try {
                    timeSource.waitOn(monitor, Math.min(POLL_INTERVAL_MS, remaining));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void cleanupFailedModern(ListenerRegistration registration, int previousMode) {
        safeClose(registration);
        try { platform.clearCommunicationDevice(); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Unable to clear rejected Bluetooth route", failure);
        }
        restoreMode(previousMode);
    }

    private void cleanupFailedLegacy(ListenerRegistration registration, int previousMode) {
        safeClose(registration);
        try { platform.stopLegacySco(); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Unable to stop rejected SCO route", failure);
        }
        restoreMode(previousMode);
    }

    private void restoreMode(int previousMode) {
        try {
            if (platform.getAudioMode() == AudioManager.MODE_IN_COMMUNICATION) {
                platform.setAudioMode(previousMode);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to restore audio mode", failure);
        }
    }

    private static void safeClose(ListenerRegistration registration) {
        if (registration == null) return;
        try { registration.close(); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Unable to remove route listener", failure);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    static RouteDevice selectBluetoothDevice(List<RouteDevice> devices) {
        if (devices == null || devices.isEmpty()) return null;
        int[] preference = {
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID
        };
        for (int type : preference) {
            for (RouteDevice device : devices) {
                if (device != null && device.type == type) return device;
            }
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    static String routeLabel(RouteDevice device) {
        if (device == null) return "default";
        if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "bluetooth-le-headset";
        if (device.type == AudioDeviceInfo.TYPE_HEARING_AID) return "bluetooth-hearing-aid";
        return "bluetooth-sco";
    }

    private static long safeAdd(long value, long delta) {
        long result = value + delta;
        return result < value ? Long.MAX_VALUE : result;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { platform.close(); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Audio route platform cleanup failed", failure);
        }
    }

    private static final class SystemTimeSource implements TimeSource {
        @Override public long nowMs() { return SystemClock.elapsedRealtime(); }
        @Override public void waitOn(Object monitor, long millis) throws InterruptedException {
            monitor.wait(Math.max(1L, millis));
        }
    }

    private static final class AndroidPlatform implements Platform {
        private final Context context;
        private final AudioManager audioManager;
        private final Map<Integer, AudioDeviceInfo> communicationDevices = new HashMap<>();

        AndroidPlatform(Context context) {
            this.context = context;
            this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) throw new IllegalStateException("AudioManager is unavailable");
        }

        @Override public int apiLevel() { return Build.VERSION.SDK_INT; }

        @Override
        public boolean hasBluetoothConnectPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }

        @Override public int getAudioMode() { return audioManager.getMode(); }
        @Override public void setAudioMode(int mode) { audioManager.setMode(mode); }

        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public List<RouteDevice> getAvailableCommunicationDevices() {
            communicationDevices.clear();
            List<AudioDeviceInfo> devices = Api31.getAvailableCommunicationDevices(audioManager);
            if (devices == null || devices.isEmpty()) return Collections.emptyList();
            List<RouteDevice> result = new ArrayList<>(devices.size());
            for (AudioDeviceInfo device : devices) {
                communicationDevices.put(device.getId(), device);
                result.add(new RouteDevice(device.getId(), device.getType()));
            }
            return result;
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public int getCurrentCommunicationDeviceId() {
            AudioDeviceInfo current = Api31.getCommunicationDevice(audioManager);
            return current == null ? -1 : current.getId();
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public boolean setCommunicationDevice(int deviceId) {
            AudioDeviceInfo device = communicationDevices.get(deviceId);
            return device != null && Api31.setCommunicationDevice(audioManager, device);
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public ListenerRegistration addCommunicationDeviceListener(
                CommunicationDeviceListener listener) {
            return Api31.addCommunicationDeviceListener(audioManager, listener);
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public void clearCommunicationDevice() {
            Api31.clearCommunicationDevice(audioManager);
        }

        @SuppressWarnings("deprecation")
        @Override public boolean isLegacyScoConnected() { return audioManager.isBluetoothScoOn(); }

        @SuppressWarnings("deprecation")
        @Override
        public ListenerRegistration addLegacyScoListener(LegacyScoListener listener) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (!AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) return;
                    int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR);
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        listener.onScoStateChanged(true);
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                            || state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                        listener.onScoStateChanged(false);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            Intent sticky = context.registerReceiver(receiver, filter);
            if (sticky != null) receiver.onReceive(context, sticky);
            return new ListenerRegistration() {
                private boolean removed;
                @Override public void close() {
                    if (removed) return;
                    removed = true;
                    try { context.unregisterReceiver(receiver); }
                    catch (IllegalArgumentException ignored) { }
                }
            };
        }

        @SuppressWarnings("deprecation")
        @Override public void startLegacySco() {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }

        @SuppressWarnings("deprecation")
        @Override public void stopLegacySco() {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
        }

        @Override public void close() { communicationDevices.clear(); }
    }
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        private Api31() { }

        static List<AudioDeviceInfo> getAvailableCommunicationDevices(AudioManager manager) {
            return manager.getAvailableCommunicationDevices();
        }

        static AudioDeviceInfo getCommunicationDevice(AudioManager manager) {
            return manager.getCommunicationDevice();
        }

        static boolean setCommunicationDevice(AudioManager manager, AudioDeviceInfo device) {
            return manager.setCommunicationDevice(device);
        }

        static ListenerRegistration addCommunicationDeviceListener(
                AudioManager manager, CommunicationDeviceListener listener) {
            Executor directExecutor = Runnable::run;
            AudioManager.OnCommunicationDeviceChangedListener platformListener =
                    device -> listener.onDeviceChanged(device == null ? -1 : device.getId());
            manager.addOnCommunicationDeviceChangedListener(directExecutor, platformListener);
            return () -> manager.removeOnCommunicationDeviceChangedListener(platformListener);
        }

        static void clearCommunicationDevice(AudioManager manager) {
            manager.clearCommunicationDevice();
        }
    }

}
