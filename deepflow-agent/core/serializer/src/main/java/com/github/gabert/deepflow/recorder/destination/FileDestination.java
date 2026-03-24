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
import java.util.LinkedHashMap;
import java.util.Map;

public class FileDestination implements Destination {
    private final Path sessionDir;
    private final String runTimestamp;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();

    public FileDestination(Map<String, String> config) {
        String dumpLocation = config.get("session_dump_location");
        if (dumpLocation == null) {
            throw new IllegalArgumentException("session_dump_location is required for file destination");
        }
        this.runTimestamp = generateRunTimestamp();
        this.sessionDir = Paths.get(dumpLocation).resolve("SESSION-" + runTimestamp);
    }

    @Override
    public void accept(byte[] record) {
        RecordRenderer.Result result = RecordRenderer.render(record);
        if (result.threadName() == null) return;

        try {
            BufferedWriter writer = writerFor(result.threadName());
            for (String line : result.lines()) {
                writer.write(line);
                writer.newLine();
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
