package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ZipDestinationTest {

    private static final String SIGNATURE = "com.example::Foo.bar() -> void [public]";

    // --- Config validation ---

    @Test
    void missingDumpLocationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZipDestination(Map.of("session_id", "test")));
    }

    // --- Single thread produces one zip entry ---

    @Test
    void singleThreadProducesOneEntry(@TempDir Path tempDir) throws Exception {
        String sessionId = "sess-001";
        ZipDestination dest = createDestination(tempDir, sessionId);

        byte[] entry = RecordWriter.logEntry(SIGNATURE, "main", 1000L, 10, 0, null, Codec.encode(new Object[]{}));
        byte[] exit = RecordWriter.logExit("main", 2000L, null, true);

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path zipPath = tempDir.resolve("SESSION-" + sessionId + ".zip");
        assertTrue(zipPath.toFile().exists());

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            List<String> entryNames = Collections.list(zf.entries()).stream()
                    .map(ZipEntry::getName)
                    .toList();

            assertEquals(1, entryNames.size());
            assertEquals(sessionId + "-main.dft", entryNames.get(0));

            List<String> lines = readZipEntry(zf, entryNames.get(0));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("MS;")));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("RT;")));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("TE;")));
        }
    }

    // --- Two threads produce two zip entries ---

    @Test
    void twoThreadsProduceTwoEntries(@TempDir Path tempDir) throws Exception {
        String sessionId = "sess-002";
        ZipDestination dest = createDestination(tempDir, sessionId);

        byte[] mainEntry = RecordWriter.logEntry(SIGNATURE, "main", 1000L, 10, 0, null, Codec.encode(new Object[]{}));
        byte[] mainExit = RecordWriter.logExit("main", 2000L, null, true);

        byte[] workerEntry = RecordWriter.logEntry(SIGNATURE, "worker-1", 1500L, 20, 0, null, Codec.encode(new Object[]{}));
        byte[] workerExit = RecordWriter.logExit("worker-1", 2500L, null, true);

        dest.accept(mainEntry);
        dest.accept(workerEntry);
        dest.accept(mainExit);
        dest.accept(workerExit);
        dest.close();

        Path zipPath = tempDir.resolve("SESSION-" + sessionId + ".zip");

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            List<String> entryNames = Collections.list(zf.entries()).stream()
                    .map(ZipEntry::getName)
                    .sorted()
                    .toList();

            assertEquals(2, entryNames.size());
            assertTrue(entryNames.contains(sessionId + "-main.dft"));
            assertTrue(entryNames.contains(sessionId + "-worker-1.dft"));

            // Each entry should have lines
            List<String> mainLines = readZipEntry(zf, sessionId + "-main.dft");
            List<String> workerLines = readZipEntry(zf, sessionId + "-worker-1.dft");
            assertFalse(mainLines.isEmpty());
            assertFalse(workerLines.isEmpty());
        }
    }

    // --- Zip entry content matches rendered output ---

    @Test
    void zipEntryContentMatchesRenderedLines(@TempDir Path tempDir) throws Exception {
        String sessionId = "sess-003";
        ZipDestination dest = createDestination(tempDir, sessionId);

        byte[] args = Codec.encode(new Object[]{"hello"});
        byte[] ret = Codec.encode(42);
        byte[] entry = RecordWriter.logEntry(SIGNATURE, "main", 1000L, 10, 0, null, args);
        byte[] exit = RecordWriter.logExit("main", 2000L, ret, false);

        // Render independently for comparison
        RecordRenderer.Result entryRendered = RecordRenderer.render(entry);
        RecordRenderer.Result exitRendered = RecordRenderer.render(exit);
        List<String> expectedLines = new ArrayList<>();
        expectedLines.addAll(entryRendered.lines());
        expectedLines.addAll(exitRendered.lines());

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path zipPath = tempDir.resolve("SESSION-" + sessionId + ".zip");
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            List<String> actualLines = readZipEntry(zf, sessionId + "-main.dft");
            assertEquals(expectedLines, actualLines);
        }
    }

    // --- Exception trace in zip ---

    @Test
    void exceptionTraceInZip(@TempDir Path tempDir) throws Exception {
        String sessionId = "sess-004";
        ZipDestination dest = createDestination(tempDir, sessionId);

        byte[] args = Codec.encode(new Object[]{});
        byte[] exc = Codec.encode(Map.of("message", "NPE"));
        byte[] entry = RecordWriter.logEntry(SIGNATURE, "main", 1000L, 10, 0, null, args);
        byte[] exit = RecordWriter.logExitException("main", 2000L, exc);

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path zipPath = tempDir.resolve("SESSION-" + sessionId + ".zip");
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            List<String> lines = readZipEntry(zf, sessionId + "-main.dft");
            assertTrue(lines.stream().anyMatch(l -> l.equals("RT;EXCEPTION")));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("RE;")));
        }
    }

    // --- Empty session produces empty zip ---

    @Test
    void noRecordsProducesEmptyZip(@TempDir Path tempDir) throws Exception {
        String sessionId = "sess-005";
        ZipDestination dest = createDestination(tempDir, sessionId);
        dest.close();

        Path zipPath = tempDir.resolve("SESSION-" + sessionId + ".zip");
        assertTrue(zipPath.toFile().exists());

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            assertEquals(0, Collections.list(zf.entries()).size());
        }
    }

    // --- Utilities ---

    private static ZipDestination createDestination(Path tempDir, String sessionId) {
        return new ZipDestination(Map.of(
                "session_dump_location", tempDir.toString(),
                "session_id", sessionId
        ));
    }

    private static List<String> readZipEntry(ZipFile zf, String entryName) throws Exception {
        ZipEntry entry = zf.getEntry(entryName);
        assertNotNull(entry, "Expected zip entry: " + entryName);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zf.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
