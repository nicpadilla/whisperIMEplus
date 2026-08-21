package com.whisperonnx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts the fixed Whisper model set from an untrusted ZIP archive. */
public final class ModelArchiveExtractor {
    public interface ProgressListener {
        void onFile(String fileName);
    }

    static final List<String> EXPECTED_FILES = Collections.unmodifiableList(Arrays.asList(
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx"));

    private static final Set<String> EXPECTED_FILE_SET =
            Collections.unmodifiableSet(new HashSet<>(EXPECTED_FILES));
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:($|/).*");
    private static final Limits DEFAULT_LIMITS = new Limits(64, 1_500_000_000L, 2_500_000_000L);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private ModelArchiveExtractor() { }

    public static void extract(InputStream source, File targetDirectory,
            ProgressListener progressListener) throws IOException {
        extract(source, targetDirectory, progressListener, DEFAULT_LIMITS);
    }

    static void extract(InputStream source, File targetDirectory,
            ProgressListener progressListener, Limits limits) throws IOException {
        if (source == null) throw new IOException("Model archive could not be opened");
        if (targetDirectory == null) throw new IOException("Model directory is unavailable");
        if (limits == null) throw new IllegalArgumentException("limits are required");

        Path targetRoot = targetDirectory.toPath().toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        Path stagingDirectory = Files.createTempDirectory(targetRoot, ".whisper-model-stage-");
        Path backupDirectory = null;

        try {
            stageArchive(source, stagingDirectory, progressListener, limits);
            backupDirectory = Files.createTempDirectory(targetRoot, ".whisper-model-backup-");
            replaceInstalledModels(targetRoot, stagingDirectory, backupDirectory);
        } finally {
            deleteRecursively(stagingDirectory);
            if (backupDirectory != null) deleteRecursively(backupDirectory);
        }
    }

    private static void stageArchive(InputStream source, Path stagingDirectory,
            ProgressListener progressListener, Limits limits) throws IOException {
        Set<String> stagedFiles = new LinkedHashSet<>();
        long totalBytes = 0L;
        int archiveEntries = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];

        try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                archiveEntries++;
                if (archiveEntries > limits.maxEntries) {
                    throw new IOException("Model archive contains too many entries");
                }

                String entryName = validateEntryName(entry.getName());
                if (entry.isDirectory()) {
                    zipInput.closeEntry();
                    continue;
                }

                Path normalizedEntry = Paths.get(entryName).normalize();
                Path fileNamePath = normalizedEntry.getFileName();
                if (fileNamePath == null) {
                    throw new IOException("Model archive contains an invalid entry name");
                }
                String fileName = fileNamePath.toString();
                if (!EXPECTED_FILE_SET.contains(fileName)) {
                    zipInput.closeEntry();
                    continue;
                }
                if (!stagedFiles.add(fileName)) {
                    throw new IOException("Model archive contains duplicate file: " + fileName);
                }
                long declaredSize = entry.getSize();
                if (declaredSize > limits.maxFileBytes) {
                    throw new IOException("Model file exceeds the allowed size: " + fileName);
                }
                if (declaredSize > 0L && totalBytes > limits.maxTotalBytes - declaredSize) {
                    throw new IOException("Model archive exceeds the allowed total size");
                }

                if (progressListener != null) progressListener.onFile(fileName);
                Path stagedFile = stagingDirectory.resolve(fileName);
                long fileBytes = 0L;
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(stagedFile,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                    int read;
                    while ((read = zipInput.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("Model installation was cancelled");
                        }
                        if (fileBytes > limits.maxFileBytes - read) {
                            throw new IOException("Model file exceeds the allowed size: " + fileName);
                        }
                        if (totalBytes > limits.maxTotalBytes - read) {
                            throw new IOException("Model archive exceeds the allowed total size");
                        }
                        output.write(buffer, 0, read);
                        fileBytes += read;
                        totalBytes += read;
                    }
                }
                if (fileBytes == 0L) {
                    throw new IOException("Model archive contains an empty file: " + fileName);
                }
                zipInput.closeEntry();
            }
        }

        if (!stagedFiles.containsAll(EXPECTED_FILES)) {
            List<String> missing = new ArrayList<>(EXPECTED_FILES);
            missing.removeAll(stagedFiles);
            throw new IOException("Model archive is missing: " + String.join(", ", missing));
        }
    }

    private static String validateEntryName(String rawName) throws IOException {
        if (rawName == null || rawName.isEmpty() || rawName.indexOf('\0') >= 0) {
            throw new IOException("Model archive contains an invalid entry name");
        }
        String slashName = rawName.replace('\\', '/');
        if (slashName.startsWith("/") || WINDOWS_DRIVE_PATH.matcher(slashName).matches()) {
            throw new IOException("Model archive contains an absolute path");
        }
        Path normalized = Paths.get(slashName).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IOException("Model archive contains a path outside the model directory");
        }
        return slashName;
    }

    private static void replaceInstalledModels(Path targetRoot, Path stagingDirectory,
            Path backupDirectory) throws IOException {
        List<String> backedUp = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        try {
            for (String fileName : EXPECTED_FILES) {
                Path installedFile = targetRoot.resolve(fileName);
                if (Files.exists(installedFile)) {
                    moveReplacing(installedFile, backupDirectory.resolve(fileName));
                    backedUp.add(fileName);
                }
            }
            for (String fileName : EXPECTED_FILES) {
                moveReplacing(stagingDirectory.resolve(fileName), targetRoot.resolve(fileName));
                installed.add(fileName);
            }
        } catch (IOException installFailure) {
            IOException rollbackFailure = null;
            for (String fileName : installed) {
                try {
                    Files.deleteIfExists(targetRoot.resolve(fileName));
                } catch (IOException failure) {
                    rollbackFailure = combine(rollbackFailure, failure);
                }
            }
            for (String fileName : backedUp) {
                Path backupFile = backupDirectory.resolve(fileName);
                if (!Files.exists(backupFile)) continue;
                try {
                    moveReplacing(backupFile, targetRoot.resolve(fileName));
                } catch (IOException failure) {
                    rollbackFailure = combine(rollbackFailure, failure);
                }
            }
            if (rollbackFailure != null) installFailure.addSuppressed(rollbackFailure);
            throw installFailure;
        }
    }

    private static IOException combine(IOException existing, IOException next) {
        if (existing == null) return next;
        existing.addSuppressed(next);
        return existing;
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup; extraction/install failure remains the primary error.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    static final class Limits {
        final int maxEntries;
        final long maxFileBytes;
        final long maxTotalBytes;

        Limits(int maxEntries, long maxFileBytes, long maxTotalBytes) {
            if (maxEntries <= 0 || maxFileBytes <= 0L || maxTotalBytes <= 0L) {
                throw new IllegalArgumentException("Archive limits must be positive");
            }
            this.maxEntries = maxEntries;
            this.maxFileBytes = maxFileBytes;
            this.maxTotalBytes = maxTotalBytes;
        }
    }
}
