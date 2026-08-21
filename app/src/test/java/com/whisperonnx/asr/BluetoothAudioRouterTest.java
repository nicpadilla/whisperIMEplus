package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothAudioRouterTest {
    @Test public void disabledUsesDefaultWithoutChangingPlatform() {
        FakePlatform platform = new FakePlatform(31);
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());
        BluetoothAudioRouter.RouteSession session = router.open(false, 1000L, () -> false);

        assertEquals("default", session.getRequestedRoute());
        assertEquals("default", session.getActualRoute());
        assertFalse(session.isBluetoothActive());
        assertEquals(0, platform.setDeviceCalls);
        assertEquals(0, platform.startScoCalls);
        session.close();
        router.close();
    }

    @Test public void modernRoutePrefersBleAndCleansUp() {
        FakePlatform platform = new FakePlatform(31);
        platform.devices = Arrays.asList(
                new BluetoothAudioRouter.RouteDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                new BluetoothAudioRouter.RouteDevice(9, AudioDeviceInfo.TYPE_BLE_HEADSET));
        platform.autoConnectModern = true;
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());

        BluetoothAudioRouter.RouteSession session = router.open(true, 1000L, () -> false);
        assertEquals("bluetooth", session.getRequestedRoute());
        assertEquals("bluetooth-le-headset", session.getActualRoute());
        assertTrue(session.isBluetoothActive());
        assertEquals(9, platform.requestedDeviceId);
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.mode);

        session.close();
        assertEquals(1, platform.clearDeviceCalls);
        assertEquals(AudioManager.MODE_NORMAL, platform.mode);
        assertEquals(1, platform.modernListenerRemovals);
        router.close();
    }

    @Test public void modernPermissionDenialFallsBackWithoutRequest() {
        FakePlatform platform = new FakePlatform(31);
        platform.permission = false;
        platform.devices = Collections.singletonList(
                new BluetoothAudioRouter.RouteDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());

        BluetoothAudioRouter.RouteSession session = router.open(true, 1000L, () -> false);
        assertEquals("default-bluetooth-permission-denied", session.getActualRoute());
        assertFalse(session.isBluetoothActive());
        assertEquals(0, platform.setDeviceCalls);
        session.close();
        router.close();
    }

    @Test public void modernTimeoutFallsBackAndRestoresMode() {
        FakePlatform platform = new FakePlatform(31);
        platform.devices = Collections.singletonList(
                new BluetoothAudioRouter.RouteDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        platform.autoConnectModern = false;
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());

        BluetoothAudioRouter.RouteSession session = router.open(true, 125L, () -> false);
        assertEquals("default-bluetooth-timeout", session.getActualRoute());
        assertFalse(session.isBluetoothActive());
        assertEquals(1, platform.clearDeviceCalls);
        assertEquals(AudioManager.MODE_NORMAL, platform.mode);
        assertEquals(1, platform.modernListenerRemovals);
        session.close();
        router.close();
    }

    @Test public void modernDisconnectFallsBackOnceAndIgnoresStaleCallback() {
        FakePlatform platform = new FakePlatform(31);
        platform.devices = Collections.singletonList(
                new BluetoothAudioRouter.RouteDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        platform.autoConnectModern = true;
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());
        BluetoothAudioRouter.RouteSession session = router.open(true, 1000L, () -> false);
        BluetoothAudioRouter.CommunicationDeviceListener stale = platform.modernListener;

        platform.currentDeviceId = -1;
        stale.onDeviceChanged(-1);
        assertTrue(session.refresh());
        assertEquals("default-after-bluetooth-disconnect", session.getActualRoute());
        assertFalse(session.refresh());
        assertEquals(1, platform.clearDeviceCalls);

        stale.onDeviceChanged(7);
        assertFalse(session.refresh());
        assertEquals("default-after-bluetooth-disconnect", session.getActualRoute());
        session.close();
        router.close();
    }

    @Test public void legacyScoWaitsForConnectionAndHandlesDisconnect() {
        FakePlatform platform = new FakePlatform(30);
        platform.autoConnectLegacy = true;
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());
        BluetoothAudioRouter.RouteSession session = router.open(true, 1000L, () -> false);

        assertEquals("bluetooth-sco", session.getActualRoute());
        assertTrue(session.isBluetoothActive());
        assertEquals(1, platform.startScoCalls);

        platform.legacyConnected = false;
        platform.legacyListener.onScoStateChanged(false);
        assertTrue(session.refresh());
        assertEquals("default-after-bluetooth-disconnect", session.getActualRoute());
        assertEquals(1, platform.stopScoCalls);
        assertEquals(AudioManager.MODE_NORMAL, platform.mode);
        session.close();
        router.close();
    }

    @Test public void cancelledSetupUsesDefaultWithoutWaitingForever() {
        FakePlatform platform = new FakePlatform(31);
        platform.devices = Collections.singletonList(
                new BluetoothAudioRouter.RouteDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        BluetoothAudioRouter router = new BluetoothAudioRouter(platform, new FakeTime());

        BluetoothAudioRouter.RouteSession session = router.open(true, 1000L, cancelled::get);
        assertEquals("default-route-cancelled", session.getActualRoute());
        assertEquals(0, platform.setDeviceCalls);
        session.close();
        router.close();
    }

    private static final class FakeTime implements BluetoothAudioRouter.TimeSource {
        long now;
        @Override public long nowMs() { return now; }
        @Override public void waitOn(Object monitor, long millis) { now += Math.max(1L, millis); }
    }

    private static final class FakePlatform implements BluetoothAudioRouter.Platform {
        final int apiLevel;
        boolean permission = true;
        int mode = AudioManager.MODE_NORMAL;
        List<BluetoothAudioRouter.RouteDevice> devices = new ArrayList<>();
        boolean autoConnectModern;
        boolean autoConnectLegacy;
        int currentDeviceId = -1;
        int requestedDeviceId = -1;
        int setDeviceCalls;
        int clearDeviceCalls;
        int startScoCalls;
        int stopScoCalls;
        int modernListenerRemovals;
        int legacyListenerRemovals;
        boolean legacyConnected;
        BluetoothAudioRouter.CommunicationDeviceListener modernListener;
        BluetoothAudioRouter.LegacyScoListener legacyListener;

        FakePlatform(int apiLevel) { this.apiLevel = apiLevel; }
        @Override public int apiLevel() { return apiLevel; }
        @Override public boolean hasBluetoothConnectPermission() { return permission; }
        @Override public int getAudioMode() { return mode; }
        @Override public void setAudioMode(int mode) { this.mode = mode; }
        @Override public List<BluetoothAudioRouter.RouteDevice> getAvailableCommunicationDevices() {
            return devices;
        }
        @Override public int getCurrentCommunicationDeviceId() { return currentDeviceId; }
        @Override public boolean setCommunicationDevice(int deviceId) {
            setDeviceCalls++;
            requestedDeviceId = deviceId;
            if (autoConnectModern) {
                currentDeviceId = deviceId;
                if (modernListener != null) modernListener.onDeviceChanged(deviceId);
            }
            return true;
        }
        @Override public BluetoothAudioRouter.ListenerRegistration addCommunicationDeviceListener(
                BluetoothAudioRouter.CommunicationDeviceListener listener) {
            modernListener = listener;
            return () -> modernListenerRemovals++;
        }
        @Override public void clearCommunicationDevice() {
            clearDeviceCalls++;
            currentDeviceId = -1;
        }
        @Override public boolean isLegacyScoConnected() { return legacyConnected; }
        @Override public BluetoothAudioRouter.ListenerRegistration addLegacyScoListener(
                BluetoothAudioRouter.LegacyScoListener listener) {
            legacyListener = listener;
            return () -> legacyListenerRemovals++;
        }
        @Override public void startLegacySco() {
            startScoCalls++;
            if (autoConnectLegacy) {
                legacyConnected = true;
                if (legacyListener != null) legacyListener.onScoStateChanged(true);
            }
        }
        @Override public void stopLegacySco() {
            stopScoCalls++;
            legacyConnected = false;
        }
        @Override public void close() { }
    }
}
