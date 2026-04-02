package com.whisperonnx.asr;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecorderBluetoothTest {

    private Context context;
    private SharedPreferences sp;
    private Recorder recorder;
    private AudioManager mockAudioManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().clear().commit();
        recorder = new Recorder(context);
        mockAudioManager = mock(AudioManager.class);
    }

    @Test
    public void defaultPreference_startDoesNotCallBluetoothSco() {
        // Default is false — should NOT activate Bluetooth SCO
        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), true);

        verify(mockAudioManager, never()).startBluetoothSco();
        verify(mockAudioManager, never()).setBluetoothScoOn(true);
    }

    @Test
    public void defaultPreference_stopDoesNotCallBluetoothSco() {
        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), false);

        verify(mockAudioManager, never()).stopBluetoothSco();
        verify(mockAudioManager, never()).setBluetoothScoOn(false);
    }

    @Test
    public void bluetoothMicEnabled_startCallsBluetoothSco() {
        sp.edit().putBoolean("useBluetoothMic", true).commit();

        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), true);

        verify(mockAudioManager).startBluetoothSco();
        verify(mockAudioManager).setBluetoothScoOn(true);
    }

    @Test
    public void bluetoothMicEnabled_stopCallsBluetoothSco() {
        sp.edit().putBoolean("useBluetoothMic", true).commit();

        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), false);

        verify(mockAudioManager).stopBluetoothSco();
        verify(mockAudioManager).setBluetoothScoOn(false);
    }

    @Test
    public void bluetoothMicExplicitlyDisabled_startDoesNotCallBluetoothSco() {
        sp.edit().putBoolean("useBluetoothMic", false).commit();

        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), true);

        verify(mockAudioManager, never()).startBluetoothSco();
        verify(mockAudioManager, never()).setBluetoothScoOn(anyBoolean());
    }

    @Test
    public void bluetoothMicExplicitlyDisabled_stopDoesNotCallBluetoothSco() {
        sp.edit().putBoolean("useBluetoothMic", false).commit();

        recorder.configureBluetooth(mockAudioManager, sp.getBoolean("useBluetoothMic", false), false);

        verify(mockAudioManager, never()).stopBluetoothSco();
        verify(mockAudioManager, never()).setBluetoothScoOn(anyBoolean());
    }
}
