package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.buffer.UnboundedRecordBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RecordDrainerTest {

    // --- Records are delivered to destination ---

    @Test
    void drainsRecordsToDestination() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CollectingDestination dest = new CollectingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        buffer.offer(new byte[]{1, 2, 3});
        buffer.offer(new byte[]{4, 5, 6});

        drainer.start();
        waitUntilEmpty(buffer);
        drainer.stop();

        // First record is the version header, then the two offered records
        assertEquals(3, dest.records.size());
        assertArrayEquals(new byte[]{1, 2, 3}, dest.records.get(1));
        assertArrayEquals(new byte[]{4, 5, 6}, dest.records.get(2));
        assertTrue(dest.flushed);
    }

    // --- Drains remaining on stop ---

    @Test
    void drainsRemainingRecordsOnStop() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        LatchDestination dest = new LatchDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        drainer.start();

        // Wait for drainer to start spinning
        Thread.sleep(50);

        // Offer records and immediately stop — drainRemaining should pick them up
        for (int i = 0; i < 100; i++) {
            buffer.offer(new byte[]{(byte) i});
        }

        drainer.stop();

        // 1 version header + 100 offered records
        assertEquals(101, dest.records.size());
        assertTrue(dest.flushed);
    }

    // --- Flush is called on stop ---

    @Test
    void flushCalledOnStop() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CollectingDestination dest = new CollectingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        drainer.start();
        drainer.stop();

        assertTrue(dest.flushed);
    }

    // --- Drainer survives exception in destination ---

    @Test
    void continuesAfterDestinationException() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        FailOnceDestination dest = new FailOnceDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        buffer.offer(new byte[]{1});
        buffer.offer(new byte[]{2});

        drainer.start(); // version record triggers the simulated failure
        waitUntilEmpty(buffer);
        drainer.stop();

        // Version record caused exception, both offered records should succeed
        assertEquals(2, dest.records.size());
        assertArrayEquals(new byte[]{1}, dest.records.get(0));
        assertArrayEquals(new byte[]{2}, dest.records.get(1));
    }

    // --- Utilities ---

    private static void waitUntilEmpty(RecordBuffer buffer) throws InterruptedException {
        for (int i = 0; i < 200 && !buffer.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    // --- Test destinations ---

    private static class CollectingDestination implements Destination {
        final List<byte[]> records = new ArrayList<>();
        boolean flushed = false;

        @Override
        public void accept(byte[] record) {
            records.add(record);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {}
    }

    private static class LatchDestination implements Destination {
        final List<byte[]> records = new ArrayList<>();
        boolean flushed = false;

        @Override
        public void accept(byte[] record) {
            records.add(record);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {}
    }

    private static class FailOnceDestination implements Destination {
        final List<byte[]> records = new ArrayList<>();
        boolean flushed = false;
        private boolean failed = false;

        @Override
        public void accept(byte[] record) {
            if (!failed) {
                failed = true;
                throw new RuntimeException("simulated failure");
            }
            records.add(record);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {}
    }
}
