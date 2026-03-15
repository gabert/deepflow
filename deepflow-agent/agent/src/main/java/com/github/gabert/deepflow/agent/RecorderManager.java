package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.recorder.RecordBuffer;
import com.github.gabert.deepflow.recorder.RecordDrainer;
import com.github.gabert.deepflow.recorder.UnboundedRecordBuffer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Owns the recorder lifecycle: buffer, drainer, writer, and shutdown hook.
 */
public final class RecorderManager {
    private final RecordBuffer buffer;
    private final RecordDrainer drainer;
    private final BufferedWriter writer;

    private RecorderManager(RecordBuffer buffer, RecordDrainer drainer, BufferedWriter writer) {
        this.buffer = buffer;
        this.drainer = drainer;
        this.writer = writer;
    }

    public static RecorderManager create(AgentConfig config) {
        String dumpLocation = config.getSessionDumpLocation();
        String sessionId = config.getSessionId();

        if (dumpLocation == null) {
            System.err.println("session_dump_location not configured. Recording disabled.");
            return null;
        }

        try {
            Path sessionDir = Paths.get(dumpLocation, "SESSION-" + sessionId);
            Files.createDirectories(sessionDir);
            Path outputFile = sessionDir.resolve(sessionId + "-recorder.dft");

            BufferedWriter writer = Files.newBufferedWriter(outputFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            RecordBuffer buffer = new UnboundedRecordBuffer();
            RecordDrainer drainer = new RecordDrainer(buffer, writer);
            drainer.start();

            RecorderManager manager = new RecorderManager(buffer, drainer, writer);
            Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown));
            return manager;
        } catch (Exception e) {
            System.err.println("Failed to initialize recorder. Recording disabled.");
            e.printStackTrace();
            return null;
        }
    }

    public RecordBuffer getBuffer() {
        return buffer;
    }

    private void shutdown() {
        drainer.stop();
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Error closing recorder writer.");
            e.printStackTrace();
        }
    }
}