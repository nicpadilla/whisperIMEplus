package com.whisperonnx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ModelArchiveExtractorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void validNestedArchiveInstallsExactlyTheExpectedFiles() throws Exception {
        File target = temporaryFolder.newFolder("models");
        List<EntrySpec> entries = validEntries("nested/model/");
        entries.add(new EntrySpec("README.txt", "ignored".getBytes(StandardCharsets.UTF_8)));

        ModelArchiveExtractor.extract(new ByteArrayInputStream(zip(entries)), target, null);

        for (String fileName : ModelArchiveExtractor.EXPECTED_FILES) {
            assertArrayEquals(content(fileName), Files.readAllBytes(target.toPath().resolve(fileName)));
        }
        assertFalse(new File(target, "README.txt").exists());
        assertNoTemporaryDirectories(target);
    }

    @Test public void traversalEntryIsRejectedWithoutEscapingTarget() throws Exception {
        File parent = temporaryFolder.newFolder("parent");
        File target = new File(parent, "models");
        assertTrue(target.mkdir());
        List<EntrySpec> entries = validEntries("");
        entries.set(0, new EntrySpec("../" + ModelArchiveExtractor.EXPECTED_FILES.get(0),
                content(ModelArchiveExtractor.EXPECTED_FILES.get(0))));

        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(entries)), target, null));

        assertFalse(new File(parent, ModelArchiveExtractor.EXPECTED_FILES.get(0)).exists());
        assertNoTemporaryDirectories(target);
    }

    @Test public void windowsDriveAndBackslashTraversalAreRejected() throws Exception {
        File target = temporaryFolder.newFolder("models");
        List<EntrySpec> driveEntries = validEntries("");
        driveEntries.set(0, new EntrySpec("C:/" + ModelArchiveExtractor.EXPECTED_FILES.get(0),
                content(ModelArchiveExtractor.EXPECTED_FILES.get(0))));
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(driveEntries)), target, null));

        List<EntrySpec> traversalEntries = validEntries("");
        traversalEntries.set(0, new EntrySpec("..\\" + ModelArchiveExtractor.EXPECTED_FILES.get(0),
                content(ModelArchiveExtractor.EXPECTED_FILES.get(0))));
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(traversalEntries)), target, null));
    }

    @Test public void duplicateExpectedBasenameIsRejected() throws Exception {
        File target = temporaryFolder.newFolder("models");
        List<EntrySpec> entries = validEntries("");
        String duplicate = ModelArchiveExtractor.EXPECTED_FILES.get(0);
        entries.add(new EntrySpec("another/" + duplicate, content(duplicate)));

        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(entries)), target, null));
        assertNoTemporaryDirectories(target);
    }

    @Test public void missingAndEmptyExpectedFilesAreRejected() throws Exception {
        File missingTarget = temporaryFolder.newFolder("missing-models");
        List<EntrySpec> missing = validEntries("");
        missing.remove(missing.size() - 1);
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(missing)), missingTarget, null));

        File emptyTarget = temporaryFolder.newFolder("empty-models");
        List<EntrySpec> empty = validEntries("");
        empty.set(2, new EntrySpec(ModelArchiveExtractor.EXPECTED_FILES.get(2), new byte[0]));
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(empty)), emptyTarget, null));
    }

    @Test public void configuredSizeAndEntryLimitsAreEnforced() throws Exception {
        File sizeTarget = temporaryFolder.newFolder("size-limited");
        ModelArchiveExtractor.Limits tinySize = new ModelArchiveExtractor.Limits(64, 2L, 100L);
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(validEntries(""))), sizeTarget, null, tinySize));

        File entryTarget = temporaryFolder.newFolder("entry-limited");
        ModelArchiveExtractor.Limits tinyEntryCount =
                new ModelArchiveExtractor.Limits(2, 1_000L, 10_000L);
        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(validEntries(""))), entryTarget, null,
                tinyEntryCount));
    }

    @Test public void invalidArchivePreservesAnExistingInstallation() throws Exception {
        File target = temporaryFolder.newFolder("models");
        for (String fileName : ModelArchiveExtractor.EXPECTED_FILES) {
            Files.write(target.toPath().resolve(fileName),
                    ("old-" + fileName).getBytes(StandardCharsets.UTF_8));
        }
        List<EntrySpec> incomplete = validEntries("");
        incomplete.remove(0);

        assertThrows(IOException.class, () -> ModelArchiveExtractor.extract(
                new ByteArrayInputStream(zip(incomplete)), target, null));

        for (String fileName : ModelArchiveExtractor.EXPECTED_FILES) {
            assertArrayEquals(("old-" + fileName).getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(target.toPath().resolve(fileName)));
        }
        assertNoTemporaryDirectories(target);
    }

    private static List<EntrySpec> validEntries(String prefix) {
        List<EntrySpec> entries = new ArrayList<>();
        for (String fileName : ModelArchiveExtractor.EXPECTED_FILES) {
            entries.add(new EntrySpec(prefix + fileName, content(fileName)));
        }
        return entries;
    }

    private static byte[] content(String fileName) {
        return ("model-data-" + fileName).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(List<EntrySpec> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (EntrySpec entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name));
                output.write(entry.content);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void assertNoTemporaryDirectories(File target) throws IOException {
        try (Stream<java.nio.file.Path> children = Files.list(target.toPath())) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".whisper-model-")));
        }
    }

    private static final class EntrySpec {
        final String name;
        final byte[] content;

        EntrySpec(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }
    }
}
