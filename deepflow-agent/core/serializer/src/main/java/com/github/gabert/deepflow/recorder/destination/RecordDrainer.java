package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.record.RecordWriter;

import java.io.IOException;

/**
 * Drains records from a {@link RecordBuffer} and delivers them to a {@link Destination}.
 * Runs in a background thread.
 */
public final class RecordDrainer {
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
        try {
            destination.accept(RecordWriter.version());
        } catch (Throwable t) {
            System.err.println("Error emitting version record: " + t.getMessage());
        }
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
        while (running) {
            try {
                byte[] record = buffer.poll();
                if (record != null) {
                    destination.accept(record);
                    hasUnflushed = true;
                } else if (hasUnflushed) {
                    destination.flush();
                    hasUnflushed = false;
                } else {
                    Thread.onSpinWait();
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
