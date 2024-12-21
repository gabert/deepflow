package com.github.gabert.deepflow.serializer;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class CompressedFileDestination implements Destination {
    private final Map<String, ZipWriter> threadWriters = new HashMap<>();
    private final String DUMP_FILE_PATTERN;

    public CompressedFileDestination(Map<String, String> configMap, String sessionId) {
        String dumpLocation = Paths.get(configMap.get("session_dump_location"),
                                 "SESSION-" + sessionId).toString();
        this.DUMP_FILE_PATTERN = Paths.get(dumpLocation,
                                    sessionId + "-{THREAD_NAME}.dft").toString();

        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
    }

    @Override
    public void send(String lines, String threadName) throws IOException {
        ZipWriter writer = threadWriters.computeIfAbsent(threadName, this::getWriter);
        writer.write(lines);
    }

    private ZipWriter getWriter(String threadName) {
        String fileName = DUMP_FILE_PATTERN.replace("{THREAD_NAME}", threadName);
        try {
            return new ZipWriter(fileName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create ZipWriter for thread: " + threadName, e);
        }
    }

    private synchronized void closeAll() {
        threadWriters.forEach((threadName, writer) -> {
            try {
                writer.close();
            } catch (Exception e) {
                System.err.println("Failed to close writer for thread: " + threadName);
                e.printStackTrace();
            }
        });
        threadWriters.clear();
    }
}
