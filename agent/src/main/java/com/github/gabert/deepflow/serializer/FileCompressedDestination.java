package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.agent.AgentConfig;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileCompressedDestination implements Destination {
    private final Map<String, ZipWriter> threadWriters = new ConcurrentHashMap<>();
    private final String DUMP_FILE_PATTERN;

    public FileCompressedDestination(AgentConfig config, String sessionId) {
        String dumpLocation = Paths.get(config.getDumpLocation(), "SESSION-" + sessionId).toString();
        this.DUMP_FILE_PATTERN = Paths.get(dumpLocation,sessionId + "-{THREAD_NAME}.dft").toString();
    }

    @Override
    public void send(String lines, String threadName) throws IOException {
        ZipWriter writer = threadWriters.compute(threadName,
                                                 (k, v) -> (v == null) ? getWriter(threadName)
                                                                       : v);
        writer.write(lines);
    }

    private ZipWriter getWriter(String threadName)  {
        String fileName = DUMP_FILE_PATTERN.replace("{THREAD_NAME}", threadName);

        try {
            return new ZipWriter(fileName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
