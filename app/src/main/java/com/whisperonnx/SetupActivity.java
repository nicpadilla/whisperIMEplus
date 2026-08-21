package com.whisperonnx;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.whisperonnx.utils.ThemeUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class SetupActivity extends AppCompatActivity {
    private static final String TAG = "SetupActivity";
    private static final Uri MODEL_DOWNLOAD_URI = Uri.parse(
            "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip");

    private final ExecutorService installerExecutor =
            Executors.newSingleThreadExecutor(new InstallerThreadFactory());
    private ActivityResultLauncher<Intent> installLauncher;
    private ProgressBar progressBar;
    private TextView extractedFileText;
    private Button startButton;
    private Button downloadButton;
    private Button installButton;
    private File modelDirectory;
    private boolean installing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        progressBar = findViewById(R.id.progress_bar);
        extractedFileText = findViewById(R.id.extracted_file);
        startButton = findViewById(R.id.button_start);
        downloadButton = findViewById(R.id.download_button);
        installButton = findViewById(R.id.install_button);
        modelDirectory = getExternalFilesDir(null);

        installLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri selectedArchive = result.getData().getData();
                    if (selectedArchive != null) installModelArchive(selectedArchive);
                });
    }

    public void downloadModel(View view) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, MODEL_DOWNLOAD_URI));
        } catch (ActivityNotFoundException failure) {
            Log.e(TAG, "No application can open the model download", failure);
            Toast.makeText(this, R.string.model_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    public void installModel(View view) {
        if (installing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        installLauncher.launch(intent);
    }

    public void startMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void installModelArchive(Uri archiveUri) {
        if (installing) return;
        installing = true;
        setInstallingUi(true);

        installerExecutor.execute(() -> {
            try (InputStream source = getContentResolver().openInputStream(archiveUri)) {
                ModelArchiveExtractor.extract(source, modelDirectory,
                        fileName -> runOnUiThread(() -> showCurrentFile(fileName)));
                runOnUiThread(() -> finishInstall(true));
            } catch (IOException | RuntimeException failure) {
                Log.e(TAG, "Model installation failed", failure);
                runOnUiThread(() -> finishInstall(false));
            }
        });
    }

    private void setInstallingUi(boolean active) {
        downloadButton.setEnabled(!active);
        installButton.setEnabled(!active);
        startButton.setEnabled(!active);
        if (active) {
            startButton.setVisibility(View.GONE);
            progressBar.setIndeterminate(true);
            progressBar.setVisibility(View.VISIBLE);
            extractedFileText.setText(R.string.model_installing);
            extractedFileText.setVisibility(View.VISIBLE);
        }
    }

    private void showCurrentFile(String fileName) {
        if (isFinishing() || isDestroyed()) return;
        extractedFileText.setText(fileName);
        extractedFileText.setVisibility(View.VISIBLE);
    }

    private void finishInstall(boolean success) {
        installing = false;
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(View.GONE);
        extractedFileText.setVisibility(View.GONE);
        downloadButton.setEnabled(true);
        installButton.setEnabled(true);
        startButton.setEnabled(true);
        startButton.setVisibility(success ? View.VISIBLE : View.GONE);
        Toast.makeText(this,
                success ? R.string.model_install_complete : R.string.model_install_failed,
                success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        installerExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class InstallerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "whisper-model-installer");
            thread.setDaemon(true);
            return thread;
        }
    }
}
