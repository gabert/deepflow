package com.github.gabert.arachna.trace.recorder.buffer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ShardedRecordBufferTest {

    @Test
    void singleThreadPreservesFifoOrder() {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(0);
        for (int i = 0; i < 100; i++) {
            buffer.offer(new byte[]{(byte) i});
        }
        assertEquals(100, buffer.size());
        for (int i = 0; i < 100; i++) {
            assertEquals((byte) i, buffer.poll()[0]);
        }
        assertNull(buffer.poll());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void allRecordsFromMultipleThreadsAreDrainedInPerThreadOrder() throws Exception {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(0);
        int threads = 4;
        int perThread = 250;
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.offer(new byte[]{(byte) threadId, (byte) (i >> 8), (byte) i});
                }
                done.countDown();
            }).start();
        }
        done.await();

        // Drain everything; verify per-thread sequences are strictly increasing.
        int[] lastSeen = new int[threads];
        java.util.Arrays.fill(lastSeen, -1);
        int total = 0;
        byte[] record;
        while ((record = buffer.poll()) != null) {
            int threadId = record[0];
            int seq = ((record[1] & 0xFF) << 8) | (record[2] & 0xFF);
            assertTrue(seq > lastSeen[threadId],
                    "per-thread FIFO order must be preserved within a shard");
            lastSeen[threadId] = seq;
            total++;
        }
        assertEquals(threads * perThread, total);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void boundedShardDropsNewRecordsAndCountsThem() {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(10);
        for (int i = 0; i < 25; i++) {
            buffer.offer(new byte[]{(byte) i});
        }
        assertEquals(10, buffer.size(), "shard must not grow past the cap");
        assertEquals(15, buffer.droppedCount(), "every rejected record must be counted");

        // The oldest records survive (drop-new policy) — the head of the
        // trace stays intact rather than the tail overwriting it.
        assertEquals((byte) 0, buffer.poll()[0]);
    }

    @Test
    void unboundedByDefaultNeverDrops() {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(0);
        for (int i = 0; i < 10_000; i++) {
            buffer.offer(new byte[]{1});
        }
        assertEquals(10_000, buffer.size());
        assertEquals(0, buffer.droppedCount());
    }

    @Test
    void deadThreadsShardIsDrainedThenPruned() throws Exception {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(0);
        Thread producer = new Thread(() -> {
            buffer.offer(new byte[]{42});
            buffer.offer(new byte[]{43});
        });
        producer.start();
        producer.join();

        // Records from the dead thread are still delivered.
        Set<Byte> drained = new HashSet<>();
        byte[] record;
        while ((record = buffer.poll()) != null) {
            drained.add(record[0]);
        }
        assertEquals(Set.of((byte) 42, (byte) 43), drained);
        assertTrue(buffer.isEmpty());

        // Poll on the now-empty buffer prunes the dead shard (weak ref may
        // need a GC to clear; tolerate either outcome, but never a record).
        System.gc();
        assertNull(buffer.poll());
        assertNull(buffer.poll());
    }

    @Test
    void drainerRoundRobinDoesNotStarveOtherThreads() throws Exception {
        ShardedRecordBuffer buffer = new ShardedRecordBuffer(0);
        int threads = 3;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final byte id = (byte) t;
            new Thread(() -> {
                for (int i = 0; i < 50; i++) buffer.offer(new byte[]{id});
                done.countDown();
            }).start();
        }
        done.await();

        // The first `threads` polls must come from distinct shards.
        List<Byte> firstFew = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            firstFew.add(buffer.poll()[0]);
        }
        assertEquals(threads, new HashSet<>(firstFew).size(),
                "round-robin must visit each producer's shard in turn");
    }
}
