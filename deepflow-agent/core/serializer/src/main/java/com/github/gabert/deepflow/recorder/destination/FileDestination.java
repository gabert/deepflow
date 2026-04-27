package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.config.ConfigLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FileDestination implements Destination {
    private static final String DEFAULT_EMIT_TAGS = "SI,TN,RI,TS,CL,TI,AR,RT,RE,TE";

    private final Path sessionDir;
    private final String runTimestamp;
    private final Set<String> emitTags;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private final List<String> pendingHeader = new ArrayList<>();

    public FileDestination(Map<String, String> config) {
        String dumpLocation = config.get("session_dump_location");
        if (dumpLocation == null) {
            throw new IllegalArgumentException("session_dump_location is required for file destination");
        }
        this.runTimestamp = generateRunTimestamp();
        this.sessionDir = Paths.get(dumpLocation).resolve("SESSION-" + runTimestamp);
        this.emitTags = ConfigLoader.parseEmitTags(config.get("emit_tags"), DEFAULT_EMIT_TAGS);
    }

    @Override
    public void accept(byte[] record) {
        RecordRenderer.Result result = RecordHashEnricher.enrich(
                RecordRenderer.render(record, emitTags));
        if (result.threadName() == null) {
            // Version record has no thread — buffer its lines for file headers
            pendingHeader.addAll(result.lines());
            return;
        }

        try {
            BufferedWriter writer = writerFor(result.threadName());
            for (String line : result.lines()) {
                writer.write(line);
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error writing trace record: " + e.getMessage());
        }
    }

    @Override
    public void flush() throws IOException {
        for (BufferedWriter writer : writers.values()) {
            writer.flush();
        }
    }

    @Override
    public void close() throws IOException {
        for (BufferedWriter writer : writers.values()) {
            writer.close();
        }
        writers.clear();
    }

    private BufferedWriter writerFor(String threadName) throws IOException {
        BufferedWriter writer = writers.get(threadName);
        if (writer == null) {
            Files.createDirectories(sessionDir);
            Path filePath = sessionDir.resolve(runTimestamp + "-" + threadName + ".dft");
            writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            for (String line : pendingHeader) {
                writer.write(line);
                writer.write('\n');
            }
            writers.put(threadName, writer);
        }
        return writer;
    }

    private static String generateRunTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        return now.format(formatter);
    }
}
