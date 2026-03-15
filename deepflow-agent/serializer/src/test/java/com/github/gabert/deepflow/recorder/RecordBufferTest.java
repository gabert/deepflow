package com.github.gabert.deepflow.recorder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class RecordBufferTest {

    // --- Basic offer / poll ---

    @Test
    void offerAndPollSingleRecord() {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        byte[] record = new byte[]{1, 2, 3};

        buffer.offer(record);
        assertEquals(1, buffer.size());

        byte[] polled = buffer.poll();
        assertArrayEquals(record, polled);
        assertEquals(0, buffer.size());
    }

    @Test
    void pollFromEmptyReturnsNull() {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        assertNull(buffer.poll());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void offerAndPollMultipleRecords() {
        RecordBuffer buffer = new UnboundedRecordBuffer();

        buffer.offer(new byte[]{1});
        buffer.offer(new byte[]{2});
        buffer.offer(new byte[]{3});
        assertEquals(3, buffer.size());
        assertFalse(buffer.isEmpty());

        assertArrayEquals(new byte[]{1}, buffer.poll());
        assertArrayEquals(new byte[]{2}, buffer.poll());
        assertArrayEquals(new byte[]{3}, buffer.poll());
        assertNull(buffer.poll());
        assertTrue(buffer.isEmpty());
    }

    // --- Never drops ---

    @Test
    void neverDropsUnderLoad() {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        int count = 10_000;

        for (int i = 0; i < count; i++) {
            buffer.offer(new byte[]{(byte) (i & 0xFF)});
        }
        assertEquals(count, buffer.size());

        for (int i = 0; i < count; i++) {
            byte[] polled = buffer.poll();
            assertNotNull(polled);
            assertEquals((byte) (i & 0xFF), polled[0]);
        }
        assertTrue(buffer.isEmpty());
    }

    // --- Concurrent usage ---

    @Test
    void producerConsumerConcurrent() throws Exception {
        int recordCount = 100_000;
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<byte[]> consumed = new ArrayList<>();

        // Consumer thread
        Thread consumer = new Thread(() -> {
            consumerReady.countDown();
            int received = 0;
            while (received < recordCount) {
                byte[] record = buffer.poll();
                if (record != null) {
                    consumed.add(record);
                    received++;
                }
            }
            done.countDown();
        });
        consumer.start();
        consumerReady.await();

        // Producer (this thread)
        for (int i = 0; i < recordCount; i++) {
            buffer.offer(new byte[]{(byte) (i & 0xFF)});
        }

        done.await();
        assertEquals(recordCount, consumed.size());

        // Verify ordering preserved
        for (int i = 0; i < recordCount; i++) {
            assertEquals((byte) (i & 0xFF), consumed.get(i)[0]);
        }
    }
}