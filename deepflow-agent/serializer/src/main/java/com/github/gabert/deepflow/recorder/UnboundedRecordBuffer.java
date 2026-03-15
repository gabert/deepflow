package com.github.gabert.deepflow.recorder;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Unbounded {@link RecordBuffer} backed by {@link ConcurrentLinkedQueue}.
 *
 * <p>Never blocks, never drops. Memory grows during bursts and is
 * reclaimed by GC once the consumer catches up.
 */
public final class UnboundedRecordBuffer implements RecordBuffer {
    private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void offer(byte[] record) {
        queue.add(record);
    }

    @Override
    public byte[] poll() {
        return queue.poll();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}