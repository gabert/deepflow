package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.recorder.*;

import java.io.IOException;
import java.util.Map;

/**
 * Owns the recorder lifecycle: buffer, drainer, destination, and shutdown hook.
 */
public final class RecorderManager {
    private final RecordBuffer buffer;
    private final RecordDrainer drainer;
    private final Destination destination;

    private RecorderManager(RecordBuffer buffer, RecordDrainer drainer, Destination destination) {
        this.buffer = buffer;
        this.drainer = drainer;
        this.destination = destination;
    }

    public static RecorderManager create(AgentConfig config) {
        try {
            Map<String, String> destinationConfig = Map.of(
                    "session_dump_location", config.getSessionDumpLocation(),
                    "session_id", config.getSessionId()
            );

            Destination destination = createDestination(config.getDestination(), destinationConfig);

            RecordBuffer buffer = new UnboundedRecordBuffer();
            RecordDrainer drainer = new RecordDrainer(buffer, destination);
            drainer.start();

            RecorderManager manager = new RecorderManager(buffer, drainer, destination);
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

    // --- Destination factory ---

    private static Destination createDestination(String type, Map<String, String> config) {
        if ("zip".equals(type)) {
            return new ZipDestination(config);
        }
        throw new IllegalArgumentException("Unknown destination type: " + type);
    }

    // --- Lifecycle ---

    private void shutdown() {
        drainer.stop();
        try {
            destination.close();
        } catch (IOException e) {
            System.err.println("Error closing destination.");
            e.printStackTrace();
        }
    }
}
