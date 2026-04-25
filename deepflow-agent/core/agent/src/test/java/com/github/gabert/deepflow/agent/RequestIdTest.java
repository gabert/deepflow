package com.github.gabert.deepflow.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdTest {

    @BeforeEach
    void resetThreadLocals() {
        DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0] = 0L;
        DeepFlowAdvice.DEPTH.get()[0] = 0;
    }

    // --- Layer 1: depth-based request ID ---

    @Test
    void rootEntryGeneratesNewRequestId() {
        int[] depth = DeepFlowAdvice.DEPTH.get();
        long[] requestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get();

        // Simulate recordEntry at depth 0
        assertEquals(0, depth[0]);
        // depth == 0 triggers new request ID
        // (we test the logic directly, not through recordEntry which needs RECORD_BUFFER)
        simulateEnter(depth, requestId);
        long firstId = requestId[0];
        assertTrue(firstId > 0);
        assertEquals(1, depth[0]);

        // Nested call inherits the same request ID
        simulateEnter(depth, requestId);
        assertEquals(firstId, requestId[0]);
        assertEquals(2, depth[0]);

        // Exit nested
        simulateExit(depth);
        assertEquals(1, depth[0]);

        // Exit root
        simulateExit(depth);
        assertEquals(0, depth[0]);

        // New root entry gets a different request ID
        simulateEnter(depth, requestId);
        long secondId = requestId[0];
        assertNotEquals(firstId, secondId);
        assertEquals(1, depth[0]);

        simulateExit(depth);
    }

    @Test
    void depthNeverGoesBelowZero() {
        int[] depth = DeepFlowAdvice.DEPTH.get();

        assertEquals(0, depth[0]);
        simulateExit(depth); // extra exit
        assertEquals(0, depth[0]);
        simulateExit(depth); // another extra exit
        assertEquals(0, depth[0]);
    }

    @Test
    void threadPoolReuseGetsDifferentRequestIds() {
        int[] depth = DeepFlowAdvice.DEPTH.get();
        long[] requestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get();

        // Request 1
        simulateEnter(depth, requestId);
        long id1 = requestId[0];
        simulateEnter(depth, requestId);
        assertEquals(id1, requestId[0]); // nested inherits
        simulateExit(depth);
        simulateExit(depth);
        assertEquals(0, depth[0]);

        // Request 2 on same thread
        simulateEnter(depth, requestId);
        long id2 = requestId[0];
        assertNotEquals(id1, id2);
        simulateExit(depth);
        assertEquals(0, depth[0]);

        // Request 3 on same thread
        simulateEnter(depth, requestId);
        long id3 = requestId[0];
        assertNotEquals(id2, id3);
        simulateExit(depth);
    }

    // --- Layer 2: PropagatingRunnable ---

    @Test
    void propagatingRunnableCarriesRequestId() throws Exception {
        int[] depth = DeepFlowAdvice.DEPTH.get();
        long[] requestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get();

        // Simulate a request on the submitting thread
        simulateEnter(depth, requestId);
        long parentId = requestId[0];

        AtomicLong capturedId = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            capturedId.set(DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0]);
            latch.countDown();
        }, parentId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        assertEquals(parentId, capturedId.get());

        simulateExit(depth);
    }

    @Test
    void propagatingRunnableRestoresState() throws Exception {
        int[] depth = DeepFlowAdvice.DEPTH.get();
        long[] requestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get();

        simulateEnter(depth, requestId);
        long parentId = requestId[0];

        AtomicLong depthAfter = new AtomicLong(-1);
        AtomicLong idAfter = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            // Inside the task, state is set
        }, parentId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        // Run the propagating task, then check state is restored
        executor.execute(() -> {
            // Before: clean state
            long priorId = DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0];
            int priorDepth = DeepFlowAdvice.DEPTH.get()[0];

            task.run();

            // After: state must be restored
            depthAfter.set(DeepFlowAdvice.DEPTH.get()[0]);
            idAfter.set(DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0]);
            assertEquals(priorDepth, depthAfter.get());
            assertEquals(priorId, idAfter.get());
            latch.countDown();
        });
        latch.await();
        executor.shutdown();

        simulateExit(depth);
    }

    @Test
    void propagatingRunnableRestoresOnException() throws Exception {
        int[] depth = DeepFlowAdvice.DEPTH.get();
        long[] requestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get();

        simulateEnter(depth, requestId);
        long parentId = requestId[0];

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong depthAfter = new AtomicLong(-1);

        Runnable task = new PropagatingRunnable(() -> {
            throw new RuntimeException("boom");
        }, parentId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException ignored) {
            }
            depthAfter.set(DeepFlowAdvice.DEPTH.get()[0]);
            latch.countDown();
        });
        latch.await();
        executor.shutdown();

        assertEquals(0, depthAfter.get());

        simulateExit(depth);
    }

    // --- Helpers mimicking DeepFlowAdvice.recordEntry/recordExit logic ---

    private static final AtomicLong TEST_COUNTER = new AtomicLong(0);

    static {
        // Use a separate counter range so tests don't collide with the real counter
        TEST_COUNTER.set(1000);
    }

    private void simulateEnter(int[] depthHolder, long[] requestIdHolder) {
        if (depthHolder[0] == 0) {
            requestIdHolder[0] = TEST_COUNTER.incrementAndGet();
        }
        depthHolder[0]++;
    }

    private void simulateExit(int[] depthHolder) {
        if (depthHolder[0] > 0) {
            depthHolder[0]--;
        }
    }
}
