package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.buffer.UnboundedRecordBuffer;
import com.github.gabert.deepflow.recorder.destination.Destination;
import com.github.gabert.deepflow.recorder.destination.DestinationRegistry;
import com.github.gabert.deepflow.recorder.destination.RecordDrainer;

import java.io.IOException;

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
            Destination destination = DestinationRegistry.create(
                    config.getDestination(), config.getConfigMap());

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
