package com.github.gabert.deepflow.recorder.destination;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class FileDestination implements Destination {
    private static final String DEFAULT_EMIT_TAGS = "MS,SI,TN,CI,PI,TS,CL,TI,AR,RT,RE,TE";

    private final Path sessionDir;
    private final String runTimestamp;
    private final Set<String> emitTags;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();

    public FileDestination(Map<String, String> config) {
        String dumpLocation = config.get("session_dump_location");
        if (dumpLocation == null) {
            throw new IllegalArgumentException("session_dump_location is required for file destination");
        }
        this.runTimestamp = generateRunTimestamp();
        this.sessionDir = Paths.get(dumpLocation).resolve("SESSION-" + runTimestamp);

        String tagsValue = config.getOrDefault("emit_tags", DEFAULT_EMIT_TAGS);
        Set<String> tags = new LinkedHashSet<>();
        tags.add("MS");
        for (String tag : tagsValue.split(",")) {
            String t = tag.trim().toUpperCase();
            if (!t.isEmpty()) tags.add(t);
        }
        this.emitTags = Collections.unmodifiableSet(tags);
    }

    @Override
    public void accept(byte[] record) {
        RecordRenderer.Result result = RecordRenderer.render(record, emitTags);
        if (result.threadName() == null) return;

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
