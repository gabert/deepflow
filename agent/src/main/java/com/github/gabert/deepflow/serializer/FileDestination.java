package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.agent.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileDestination implements Destination {
    private final Map<String, Path> dumpPaths = new ConcurrentHashMap<>();
    private final String DUMP_FILE_PATTERN;

    public FileDestination (AgentConfig config, String sessionId) {
        String dumpLocation = Paths.get(config.getDumpLocation(), "SESSION-" + sessionId).toString();
        this.DUMP_FILE_PATTERN = Paths.get(dumpLocation,sessionId + "-{THREAD_NAME}.dft").toString();

        try {
            ensurePath(dumpLocation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void send(String line, String threadName) throws IOException {
        Path filePath = dumpPaths.compute(threadName, (k, v) -> (v == null)
                ? Paths.get(DUMP_FILE_PATTERN.replace("{THREAD_NAME}", threadName))
                : v);

        Files.write(filePath, line.getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void ensurePath(String directoryPath) throws IOException {
       Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }
}
