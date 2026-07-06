package com.github.gabert.arachna.trace.agent.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Symmetric companion to {@code RequestIdTest}'s PropagatingRunnable suite.
 * PropagatingCallable is the wire that ForkJoinPool.submit(Callable) rides
 * for request-id and call-stack propagation, so the Callable variant of
 * each contract is tested here independently.
 */
class PropagatingCallableTest {

    @BeforeEach
    void resetThreadLocals() {
        RequestContext.reset();
    }

    @Test
    void carriesRequestIdToWorker() throws Exception {
        AtomicLong captured = new AtomicLong(0);
        Callable<String> task = new PropagatingCallable<>(() -> {
            captured.set(RequestContext.currentRequestId());
            return "ok";
        }, 4242L, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(task);
        assertEquals("ok", future.get());
        executor.shutdown();

        assertEquals(4242L, captured.get());
    }

    @Test
    void propagatesReturnValue() throws Exception {
        // Unique to Callable (Runnable has no return). A regression where
        // callScoped returns null instead of body.call() would surface here.
        Callable<Integer> task = new PropagatingCallable<>(() -> 17, 1L, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> future = executor.submit(task);
        assertEquals(17, future.get());
        executor.shutdown();
    }

    @Test
    void restoresStateAfterNormalCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicLong depthAfter = new AtomicLong(-1);
        AtomicLong idAfter = new AtomicLong(-1);

        executor.submit(() -> {
            long priorId = RequestContext.currentRequestId();
            int priorDepth = RequestContext.currentDepth();

            Callable<String> task = new PropagatingCallable<>(() -> "ok", 99L, null);
            task.call();

            depthAfter.set(RequestContext.currentDepth());
            idAfter.set(RequestContext.currentRequestId());
            assertEquals(priorDepth, depthAfter.get());
            assertEquals(priorId, idAfter.get());
            return null;
        }).get();
        executor.shutdown();
    }

    @Test
    void restoresStateOnException() throws Exception {
        // callScoped's finally must restore state on the exception path too;
        // a worker thread that gets a throwing body cannot keep depth=1
        // bleeding into the next pool task it runs.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicLong depthAfter = new AtomicLong(-1);

        Future<?> future = executor.submit(() -> {
            Callable<String> task = new PropagatingCallable<>(() -> {
                throw new RuntimeException("boom");
            }, 7L, null);
            try {
                task.call();
                fail("expected throw");
            } catch (Exception ignored) {
            }
            depthAfter.set(RequestContext.currentDepth());
            return null;
        });
        future.get();
        executor.shutdown();

        assertEquals(0, depthAfter.get());
    }

    @Test
    void seedsAndRestoresCallStack() throws Exception {
        // Worker sees parentCallId at peek before any push; after the body
        // returns, its CALL_STACK is fully restored (no leftover).
        UUID parent = UUID.randomUUID();
        AtomicReference<UUID> seenParent = new AtomicReference<>();
        AtomicReference<Boolean> stackEmptyAfter = new AtomicReference<>();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            assertTrue((RequestContext.callStackSize() == 0),
                    "worker thread starts with empty stack");

            Callable<String> task = new PropagatingCallable<>(() -> {
                seenParent.set(RequestContext.peekParentCallId());
                RequestContext.pushCallId(UUID.randomUUID());
                return "ok";
            }, 1L, parent);

            try {
                task.call();
            } catch (Exception e) {
                fail(e);
            }
            stackEmptyAfter.set((RequestContext.callStackSize() == 0));
            return null;
        }).get();
        executor.shutdown();

        assertEquals(parent, seenParent.get());
        assertTrue(stackEmptyAfter.get(),
                "worker's CALL_STACK must drain after callScoped returns");
    }

    @Test
    void exceptionFromBodyIsRethrown() throws Exception {
        Callable<String> task = new PropagatingCallable<>(() -> {
            throw new IllegalStateException("inner");
        }, 1L, null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(task);
        ExecutionException ee = assertThrows(ExecutionException.class, future::get);
        executor.shutdown();
        assertInstanceOf(IllegalStateException.class, ee.getCause());
        assertEquals("inner", ee.getCause().getMessage());
    }
}
