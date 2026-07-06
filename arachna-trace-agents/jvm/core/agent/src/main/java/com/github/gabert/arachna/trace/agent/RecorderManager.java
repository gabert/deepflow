package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.buffer.RecordBuffer;
import com.github.gabert.arachna.trace.recorder.buffer.ShardedRecordBuffer;
import com.github.gabert.arachna.trace.recorder.destination.Destination;
import com.github.gabert.arachna.trace.recorder.destination.DestinationRegistry;
import com.github.gabert.arachna.trace.recorder.destination.RecordDrainer;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Owns the recorder lifecycle: buffer, drainer, destination, and shutdown hook.
 *
 * <p>At startup, generates a per-JVM {@link AgentRun} (UUID + environment),
 * hands it to the destination via {@link Destination#setAgentRun}, and emits
 * the {@code VR} (wire-format version) record. The agent-run identity travels
 * as transport metadata (HTTP / Kafka headers, file sidecar) — there is no
 * per-record stamping and no {@code RH} wire record.</p>
 */
public final class RecorderManager {
    /**
     * Agent build version stamped onto every {@link AgentRun}. Kept in sync
     * with the project's POM version manually for now; once the agent JAR
     * carries a populated {@code Implementation-Version} manifest entry we
     * should switch to reading it from {@code Package.getImplementationVersion()}.
     */
    static final String AGENT_VERSION = "0.0.1-SNAPSHOT";

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

            AgentRun agentRun = buildAgentRun(config);
            destination.setAgentRun(agentRun);

            // Emit the version banner on the calling thread, before the
            // drainer thread starts — destination implementations
            // (e.g. FileDestination) are not synchronized; the drainer
            // will become the sole writer once started.
            try {
                destination.accept(RecordWriter.version());
            } catch (Throwable t) {
                System.err.println("[ArachnaTrace] Error emitting startup records: " + t.getMessage());
                t.printStackTrace();
            }

            RecordBuffer buffer = new ShardedRecordBuffer(config.getBufferMaxRecordsPerThread());
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

    // --- Startup record assembly ---

    private static AgentRun buildAgentRun(AgentConfig config) {
        return new AgentRun(
                UUID.randomUUID(),
                resolveHostname(),
                AGENT_VERSION,
                config.getCodeVersion(),
                config.getEnv(),
                ProcessHandle.current().pid(),
                System.currentTimeMillis());
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable t) {
            return "unknown";
        }
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
        if (buffer instanceof ShardedRecordBuffer sharded && sharded.droppedCount() > 0) {
            System.err.println("[ArachnaTrace] " + sharded.droppedCount()
                    + " record(s) were dropped this run because a thread's buffer hit "
                    + "buffer_max_records_per_thread — the trace is incomplete.");
        }
    }
}
