package com.github.gabert.arachna.trace.recorder.destination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.arachna.trace.codec.Codec;
import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;
import com.github.gabert.arachna.trace.renderer.RecordRenderer;
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

        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, null, null, Codec.encode(new Object[]{}));
        byte[] exit = RecordWriter.logExit(null, "main", 2000L, 0L, null, null, true);

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

        byte[] mainEntry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, null, null, Codec.encode(new Object[]{}));
        byte[] mainExit = RecordWriter.logExit(null, "main", 2000L, 0L, null, null, true);

        byte[] workerEntry = RecordWriter.logEntry(null, SIGNATURE, "worker-1", 1500L, 20, 1L, null, null, null, Codec.encode(new Object[]{}));
        byte[] workerExit = RecordWriter.logExit(null, "worker-1", 2500L, 1L, null, null, true);

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
        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, null, null, args);
        byte[] exit = RecordWriter.logExit(null, "main", 2000L, 0L, null, ret, false);

        Set<String> defaultTags = Set.of("MS", "SI", "TN", "RI", "TS", "CL", "TI", "AR", "RT", "RE", "TE");
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
        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, null, null, args);
        byte[] exit = RecordWriter.logExitException(null, "main", 2000L, 0L, null, exc);

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

    // --- Lines are on disk after flush() (readable before close) ---
    // The drainer calls flush() whenever its queue runs empty, so in the
    // live pipeline traces are readable whenever the application pauses;
    // accept() itself no longer flushes per record.

    @Test
    void linesVisibleAfterFlushBeforeClose(@TempDir Path tempDir) throws Exception {
        FileDestination dest = createDestination(tempDir);

        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 10, 0L, null, null, null, Codec.encode(new Object[]{}));
        dest.accept(entry);
        dest.flush();

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

    // --- setAgentRun writes a run.json sidecar ---

    @Test
    void setAgentRunWritesRunJsonSidecar(@TempDir Path tempDir) throws Exception {
        // The sidecar is how downstream tools (and the file-based UI) associate
        // a SESSION-<ts>/ directory with the agent run that produced it. If the
        // sidecar is missing or corrupt, those tools can't read the traces.
        FileDestination dest = createDestination(tempDir);

        UUID runId = UUID.randomUUID();
        AgentRun run = new AgentRun(runId, "host-x", "1.2.3", "abc", "prod", 4242L, 9_000_000L);
        dest.setAgentRun(run);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        Path sidecar = sessionDir.resolve("run.json");
        assertTrue(Files.exists(sidecar), "run.json sidecar must be written");

        JsonNode json = new ObjectMapper().readTree(sidecar.toFile());
        assertEquals(runId.toString(), json.get("agentRunId").asText());
        assertEquals("host-x", json.get("hostname").asText());
        assertEquals("1.2.3", json.get("agentVersion").asText());
        assertEquals("abc", json.get("codeVersion").asText());
        assertEquals("prod", json.get("env").asText());
        assertEquals(4242L, json.get("processPid").asLong());
        assertEquals(9_000_000L, json.get("startedAtMillis").asLong());
    }

    // --- VR record buffered and prepended to every per-thread file ---

    @Test
    void versionRecordIsPrependedToEveryThreadFile(@TempDir Path tempDir) throws Exception {
        // VR carries no thread name; FileDestination must stash it in
        // `pendingHeader` and write it as the FIRST line of every per-thread
        // .dft file as those threads come online. Untested before Round 3 —
        // a refactor breaking pendingHeader would lose the wire-format banner
        // from every output file.
        FileDestination dest = createDestination(tempDir);

        byte[] vr = RecordWriter.version((short) 1, (short) 4);
        byte[] entryMain = RecordWriter.logEntry(null, SIGNATURE, "main", 1000L, 0, 0L,
                null, null, null, Codec.encode(new Object[]{}));
        byte[] entryWorker = RecordWriter.logEntry(null, SIGNATURE, "worker-1", 1500L, 0, 0L,
                null, null, null, Codec.encode(new Object[]{}));

        dest.accept(vr);             // buffered as pending header
        dest.accept(entryMain);      // main file gets VR line first
        dest.accept(entryWorker);    // worker file gets VR line first
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        List<Path> files = listDftFiles(sessionDir);
        assertEquals(2, files.size());
        for (Path dft : files) {
            String first = Files.readAllLines(dft).get(0);
            assertEquals("VR;1.4", first,
                    "every .dft must start with the VR banner; offender: " + dft.getFileName());
        }
    }

    // --- emit_tags filter is honoured ---

    @Test
    void customEmitTagsFiltersOutput(@TempDir Path tempDir) throws Exception {
        // FileDestination passes its config emit_tags through to RecordRenderer.
        // Locking the wiring: a user who restricts emit_tags to MS+TE only
        // must see the file contain only MS and TE (no AR, no TS, no SI).
        FileDestination dest = new FileDestination(Map.of(
                "session_dump_location", tempDir.toString(),
                "emit_tags", "MS,TE"));

        byte[] entry = RecordWriter.logEntry(SESSION_ID, SIGNATURE, "main", 1000L, 10, 5L,
                null, null, null, Codec.encode(new Object[]{"x"}));
        byte[] exit = RecordWriter.logExit(SESSION_ID, "main", 2000L, 5L, null, null, true);

        dest.accept(entry);
        dest.accept(exit);
        dest.close();

        Path sessionDir = findSessionDir(tempDir);
        List<String> lines = Files.readAllLines(
                listDftFiles(sessionDir).get(0));

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("MS;")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("TE;")));
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("AR;")), "AR must be filtered");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("TS;")), "TS must be filtered");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("SI;")), "SI must be filtered");
    }

    private static final String SESSION_ID = "sess-1";

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
