package com.github.gabert.deepflow.recorder;

/**
 * Buffer for passing binary records from producer threads to a consumer (drain) thread.
 *
 * <p>Implementations must be thread-safe for concurrent offer/poll from different threads.
 */
public interface RecordBuffer {

    /**
     * Enqueue a record. Must never block the caller.
     */
    void offer(byte[] record);

    /**
     * Dequeue a record. Returns {@code null} if the buffer is empty.
     */
    byte[] poll();

    /**
     * Number of records currently in the buffer.
     */
    int size();

    /**
     * Returns {@code true} if the buffer contains no records.
     */
    boolean isEmpty();
}