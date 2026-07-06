package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.agent.bootstrap.PropagatingRunnable;
import com.github.gabert.arachna.trace.agent.bootstrap.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdTest {

    @BeforeEach
    void resetThreadLocals() {
        RequestContext.reset();
    }

    // --- Layer 1: depth-based request ID ---

    @Test
    void rootEntryGeneratesNewRequestId() {
        assertEquals(0, RequestContext.currentDepth());

        // Root entry at depth 0 triggers a new request ID
        long firstId = RequestContext.beginRequest();
        assertTrue(firstId > 0);
        assertEquals(1, RequestContext.currentDepth());

        // Nested call inherits the same request ID
        assertEquals(firstId, RequestContext.beginRequest());
        assertEquals(2, RequestContext.currentDepth());

        // Exit nested
        RequestContext.endRequest();
        assertEquals(1, RequestContext.currentDepth());

        // Exit root
        RequestContext.endRequest();
        assertEquals(0, RequestContext.currentDepth());

        // New root entry gets a different request ID
        long secondId = RequestContext.beginRequest();
        assertNotEquals(firstId, secondId);
        assertEquals(1, RequestContext.currentDepth());

        RequestContext.endRequest();
    }

    @Test
    void depthNeverGoesBelowZero() {
        assertEquals(0, RequestContext.currentDepth());
        RequestContext.endRequest(); // extra exit
        assertEquals(0, RequestContext.currentDepth());
        RequestContext.endRequest(); // another extra exit
        assertEquals(0, RequestContext.currentDepth());
    }

    @Test
    void threadPoolReuseGetsDifferentRequestIds() {
        // Request 1
        long id1 = RequestContext.beginRequest();
        assertEquals(id1, RequestContext.beginRequest()); // nested inherits
        RequestContext.endRequest();
        RequestContext.endRequest();
        assertEquals(0, RequestContext.currentDepth());

        // Request 2 on same thread
        long id2 = RequestContext.beginRequest();
        assertNotEquals(id1, id2);
        RequestContext.endRequest();
        assertEquals(0, RequestContext.currentDepth());

        // Request 3 on same thread
        long id3 = RequestContext.beginRequest();
        assertNotEquals(id2, id3);
        RequestContext.endRequest();
    }

    // --- Layer 2: PropagatingRunnable ---

    @Test
    void propagatingRunnableCarriesRequestId() throws Exception {
        // Simulate a request on the submitting thread
        long parentId = RequestContext.beginRequest();

        AtomicLong capturedId = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            capturedId.set(RequestContext.currentRequestId());
            latch.countDown();
        }, parentId, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        assertEquals(parentId, capturedId.get());

        RequestContext.endRequest();
    }

    @Test
    void propagatingRunnableRestoresState() throws Exception {
        long parentId = RequestContext.beginRequest();

        AtomicLong depthAfter = new AtomicLong(-1);
        AtomicLong idAfter = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            // Inside the task, state is set
        }, parentId, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        // Run the propagating task, then check state is restored
        executor.execute(() -> {
            // Before: clean state
            long priorId = RequestContext.currentRequestId();
            int priorDepth = RequestContext.currentDepth();

            task.run();

            // After: state must be restored
            depthAfter.set(RequestContext.currentDepth());
            idAfter.set(RequestContext.currentRequestId());
            assertEquals(priorDepth, depthAfter.get());
            assertEquals(priorId, idAfter.get());
            latch.countDown();
        });
        latch.await();
        executor.shutdown();

        RequestContext.endRequest();
    }

    @Test
    void propagatingRunnableRestoresOnException() throws Exception {
        // runScoped's finally must restore state on the exception path too —
        // a worker thread that gets a throwing body cannot keep depth=1
        // bleeding into the next pool task it runs.
        long parentId = RequestContext.beginRequest();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong depthAfter = new AtomicLong(-1);

        Runnable task = new PropagatingRunnable(() -> {
            throw new RuntimeException("boom");
        }, parentId, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException ignored) {
            }
            depthAfter.set(RequestContext.currentDepth());
            latch.countDown();
        });
        latch.await();
        executor.shutdown();

        assertEquals(0, depthAfter.get());

        RequestContext.endRequest();
    }

    // --- Layer 3: PropagatingRunnable carries parent call_id ---

    @Test
    void propagatingRunnableSeedsCallStackWithParent() throws Exception {
        UUID parentCallId = UUID.randomUUID();
        AtomicReference<UUID> capturedParent = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            // First "traced method" on the worker thread peeks for its parent
            capturedParent.set(RequestContext.peekParentCallId());
            latch.countDown();
        }, 42L, parentCallId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        assertEquals(parentCallId, capturedParent.get());
    }

    @Test
    void propagatingRunnableRestoresCallStackOnExit() throws Exception {
        // Pre-populate the submitting thread's stack with a UUID to verify
        // that the worker thread's swap doesn't bleed back here.
        UUID outerCall = UUID.randomUUID();
        RequestContext.pushCallId(outerCall);

        UUID seededParent = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);

        // Simulate a worker thread that runs a propagated task, then checks
        // that its own thread-local stack is fully restored afterwards.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Boolean> stackEmptyAfter = new AtomicReference<>();
        executor.execute(() -> {
            // The worker thread starts with an empty call stack.
            assertEquals(0, RequestContext.callStackSize());

            Runnable task = new PropagatingRunnable(() -> {
                // Inside the task: stack is seeded with parent
                assertEquals(seededParent, RequestContext.peekParentCallId());
                // Push another call to simulate a real traced method
                RequestContext.pushCallId(UUID.randomUUID());
            }, 42L, seededParent);

            task.run();

            // After the task: worker's stack must be back to empty
            stackEmptyAfter.set(RequestContext.callStackSize() == 0);
            latch.countDown();
        });
        latch.await();
        executor.shutdown();

        assertTrue(stackEmptyAfter.get(),
                "Worker's call stack must be restored to its prior state after runScoped");

        // Submitter's stack still has its outer call — async swap must not have touched it
        assertEquals(outerCall, RequestContext.peekParentCallId());
        RequestContext.popCallId();
    }

    @Test
    void propagatingRunnableWithNullParentLeavesStackEmpty() throws Exception {
        AtomicReference<UUID> capturedParent = new AtomicReference<>();
        AtomicLong stackSizeOnEntry = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = new PropagatingRunnable(() -> {
            stackSizeOnEntry.set(RequestContext.callStackSize());
            capturedParent.set(RequestContext.peekParentCallId());
            latch.countDown();
        }, 42L, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        assertEquals(0L, stackSizeOnEntry.get(),
                "null parentCallId means the stack starts empty on the worker");
        assertNull(capturedParent.get());
    }
}
