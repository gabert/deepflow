package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileDestinationTest {

    private static final String SIGNATURE = "com.example::Foo.bar() -> void [public]";

    // --- Config validation ---

    @Test
    void missingDumpLocationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileDestination(Map.of()));
    }

    // --- Single thread produces one file ---

    @Test
    void singleThreadProducesOneFile(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, Codec.encode(new Object[]{}));
        byte[] exit = RecordWriter.logExit(null, "main", 2000L, null, true);

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        List<Path> dftFiles = listDftFiles(sessionDir);

        assertEquals(1, dftFiles.size());
        assertTrue(dftFiles.get(0).getFileName().toString().endsWith("-main.dft"));

        List<String> lines = Files.readAllLines(dftFiles.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("MS;")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("RT;")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("TE;")));
    }

    // --- Two threads produce two files ---

    @Test
    void twoThreadsProduceTwoFiles(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] mainEntry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, Codec.encode(new Object[]{}));
        byte[] mainExit = RecordWriter.logExit(null, "main", 2000L, null, true);

        byte[] workerEntry = RecordWriter.logEntry(null, SIGNATURE, "worker-1", 1500L, 20, 1L, null, Codec.encode(new Object[]{}));
        byte[] workerExit = RecordWriter.logExit(null, "worker-1", 2500L, null, true);

        dest.accept(mainEntry);
        dest.accept(workerEntry);
        dest.accept(mainExit);
        dest.accept(workerExit);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        List<Path> dftFiles = listDftFiles(sessionDir);

        assertEquals(2, dftFiles.size());
        assertTrue(dftFiles.stream().anyMatch(p -> p.getFileName().toString().endsWith("-main.dft")));
        assertTrue(dftFiles.stream().anyMatch(p -> p.getFileName().toString().endsWith("-worker-1.dft")));

        for (Path dft : dftFiles) {
            assertFalse(Files.readAllLines(dft).isEmpty());
        }
    }

    // --- File content matches rendered output ---

    @Test
    void fileContentMatchesRenderedLines(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] args = Codec.encode(new Object[]{"hello"});
        byte[] ret = Codec.encode(42);
        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, args);
        byte[] exit = RecordWriter.logExit(null, "main", 2000L, ret, false);

        Set<String> defaultTags = Set.of("MS", "SI", "TN", "CI", "TS", "CL", "TI", "AR", "RT", "RE", "TE");
        RecordRenderer.Result entryRendered = RecordRenderer.render(entry, defaultTags);
        RecordRenderer.Result exitRendered = RecordRenderer.render(exit, defaultTags);
        List<String> expectedLines = new ArrayList<>();
        expectedLines.addAll(entryRendered.lines());
        expectedLines.addAll(exitRendered.lines());

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        Path mainDft = listDftFiles(sessionDir).stream()
                .filter(p -> p.getFileName().toString().endsWith("-main.dft"))
                .findFirst().orElseThrow();

        List<String> actualLines = Files.readAllLines(mainDft);
        assertEquals(expectedLines, actualLines);
    }

    // --- Exception trace in file ---

    @Test
    void exceptionTraceInFile(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] args = Codec.encode(new Object[]{});
        byte[] exc = Codec.encode(Map.of("message", "NPE"));
        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, args);
        byte[] exit = RecordWriter.logExitException(null, "main", 2000L, exc);

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        Path mainDft = listDftFiles(sessionDir).stream()
                .filter(p -> p.getFileName().toString().endsWith("-main.dft"))
                .findFirst().orElseThrow();

        List<String> lines = Files.readAllLines(mainDft);
        assertTrue(lines.stream().anyMatch(l -> l.equals("RT;EXCEPTION")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("RE;")));
    }

    // --- Lines are flushed immediately (readable before close) ---

    @Test
    void linesVisibleBeforeClose(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, Codec.encode(new Object[]{}));
        dest.accept(entry);

        // Read before close — lines should already be on disk
        Path sessionDir = findSessionDir(tempDir);
        Path mainDft = listDftFiles(sessionDir).stream()
                .filter(p -> p.getFileName().toString().endsWith("-main.dft"))
                .findFirst().orElseThrow();

        List<String> lines = Files.readAllLines(mainDft);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("MS;")));

        dest.close();
    }

    // --- No records produces empty session dir ---

    @Test
    void noRecordsProducesNoFiles(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);
        dest.close();

        // No session dir created because no records were written
        try (Stream<Path> dirs = Files.list(tempDir)) {
            assertEquals(0, dirs.count());
        }
    }

    // --- Utilities ---

    private static FileDestination createDestination(Path tempDir) {
        return new FileDestination(Map.of(
                "session_dump_location", tempDir.toString()
        ));
    }

    private static Path findSessionDir(Path dir) throws Exception {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("SESSION-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No SESSION-* dir found in " + dir));
        }
    }

    private static List<Path> listDftFiles(Path sessionDir) throws Exception {
        try (Stream<Path> paths = Files.list(sessionDir)) {
            return paths
                    .filter(p -> p.getFileName().toString().endsWith(".dft"))
                    .sorted()
                    .toList();
        }
    }
}
