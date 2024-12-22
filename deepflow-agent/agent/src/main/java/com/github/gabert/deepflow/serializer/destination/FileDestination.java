package com.github.gabert.deepflow.serializer.destination;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileDestination implements Destination {
    private final Map<String, BufferedWriter> threadWriters = new ConcurrentHashMap<>();
    private final String DUMP_FILE_PATTERN;

    public FileDestination(Map<String, String> configMap, String sessionId) {
        String dumpLocation = Paths.get(configMap.get("session_dump_location"),
                "SESSION-" + sessionId).toString();
        this.DUMP_FILE_PATTERN = Paths.get(dumpLocation,
                sessionId + "-{THREAD_NAME}.dft").toString();

        try {
            ensurePath(dumpLocation);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create dump directory: " + dumpLocation, e);
        }

        // Register a shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
    }

    @Override
    public void send(String line, String threadName) throws IOException {
        BufferedWriter writer = threadWriters.computeIfAbsent(threadName, this::getWriter);

        writer.write(line);
        writer.flush();
    }

    private BufferedWriter getWriter(String threadName) {
        String fileName = DUMP_FILE_PATTERN.replace("{THREAD_NAME}", threadName);
        try {
            Path filePath = Paths.get(fileName);
            ensurePath(filePath.getParent().toString());
            return Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create writer for thread: " + threadName, e);
        }
    }

    private static void ensurePath(String directoryPath) throws IOException {
        Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private void closeAll() {
        threadWriters.forEach((threadName, writer) -> {
            try {
                writer.close();
            } catch (IOException e) {
                System.err.println("Failed to close writer for thread: " + threadName);
                e.printStackTrace();
            }
        });
        threadWriters.clear();
    }
}
