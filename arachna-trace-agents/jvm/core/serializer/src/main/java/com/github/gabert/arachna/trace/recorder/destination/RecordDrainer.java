package com.github.gabert.arachna.trace.recorder.destination;

import com.github.gabert.arachna.trace.recorder.buffer.RecordBuffer;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/**
 * Drains records from a {@link RecordBuffer} and delivers them to a {@link Destination}.
 * Runs in a background thread.
 *
 * <p>Startup-only records (wire-format version, run-header) are emitted by
 * {@code RecorderManager} directly to the destination before {@link #start()},
 * so this class is solely responsible for the streaming drain loop.</p>
 */
public final class RecordDrainer {
    private static final int IDLE_SPIN_LIMIT = 1_000;
    private static final long IDLE_PARK_NANOS = 1_000_000L; // 1 ms

    private final RecordBuffer buffer;
    private final Destination destination;
    private final Thread thread;
    private volatile boolean running;

    // --- Public API ---

    public RecordDrainer(RecordBuffer buffer, Destination destination) {
        this.buffer = buffer;
        this.destination = destination;
        this.thread = new Thread(this::drainLoop, "record-drainer");
        this.thread.setDaemon(true);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainRemaining();
    }

    // --- Drain loop ---

    private void drainLoop() {
        boolean hasUnflushed = false;
        int idleSpins = 0;
        while (running) {
            try {
                byte[] record = buffer.poll();
                if (record != null) {
                    idleSpins = 0;
                    destination.accept(record);
                    hasUnflushed = true;
                } else if (hasUnflushed) {
                    destination.flush();
                    hasUnflushed = false;
                } else if (idleSpins < IDLE_SPIN_LIMIT) {
                    // Stay hot briefly after the queue empties so a burst
                    // resumes with no wake-up latency ...
                    idleSpins++;
                    Thread.onSpinWait();
                } else {
                    // ... then park instead of burning a core for the JVM's
                    // whole lifetime. Worst case adds IDLE_PARK_NANOS to the
                    // drain (not the app's) latency after an idle period.
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                }
            } catch (Throwable t) {
                System.err.println("Error in drain loop, skipping record.");
                t.printStackTrace();
            }
        }
    }

    private void drainRemaining() {
        byte[] record;
        while ((record = buffer.poll()) != null) {
            destination.accept(record);
        }
        try {
            destination.flush();
        } catch (IOException e) {
            System.err.println("Error flushing destination.");
            e.printStackTrace();
        }
    }
}
