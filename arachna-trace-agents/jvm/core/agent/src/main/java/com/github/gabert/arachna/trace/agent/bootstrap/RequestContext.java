package com.github.gabert.arachna.trace.agent.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local state for request ID and call-id propagation.
 *
 * <p>This class is injected into the bootstrap classloader so that advice
 * inlined into JDK classes (ThreadPoolExecutor, ForkJoinPool) can access
 * the same state as advice running in application classes.</p>
 *
 * <p>All per-thread state (request id, depth, call stack) lives in one
 * {@link State} object behind a single ThreadLocal. The recorder's hot path
 * fetches it once per entry/exit via {@link #state()} instead of paying a
 * ThreadLocal lookup per field; the static delegates exist for advice
 * call sites and tests, which touch a single field per call.</p>
 */
public class RequestContext {
    public static final AtomicLong REQUEST_COUNTER = new AtomicLong(0);

    private static final ThreadLocal<State> CONTEXT = ThreadLocal.withInitial(State::new);

    /** The calling thread's propagation state — one ThreadLocal lookup. */
    public static State state() {
        return CONTEXT.get();
    }

    /**
     * Per-thread propagation state. The call stack holds the UUIDs of
     * currently-open traced calls: pushed at method entry, popped at method
     * exit. The top is the current call; the value below the top is the
     * parent. Empty at the top of a request. Each {@code MS} record carries
     * its own UUID and its parent's UUID (or null at the root), so the
     * processor can rebuild the tree without holding state across batch
     * boundaries.
     */
    public static final class State {
        private long requestId;
        private int depth;
        private Deque<UUID> callStack = new ArrayDeque<>();

        /**
         * Begin a request: at depth 0 assign a fresh request ID, then
         * increment depth. Returns the active request ID.
         */
        public long beginRequest() {
            if (depth == 0) {
                requestId = REQUEST_COUNTER.incrementAndGet();
            }
            depth++;
            return requestId;
        }

        /**
         * End a request: decrement depth (clamped at 0). Returns the request
         * ID that was active for this exit so callers can stamp it on records.
         */
        public long endRequest() {
            long id = requestId;
            if (depth > 0) {
                depth--;
            }
            return id;
        }

        /** Returns the current call's parent UUID, or {@code null} if at the top of the stack. */
        public UUID peekParentCallId() {
            return callStack.peek();
        }

        public void pushCallId(UUID callId) {
            callStack.push(callId);
        }

        /** Pops and returns the current call's UUID; returns {@code null} if the stack is empty. */
        public UUID popCallId() {
            return callStack.isEmpty() ? null : callStack.pop();
        }
    }

    // --- Static delegates (advice call sites, tests) ---

    public static long beginRequest() {
        return state().beginRequest();
    }

    public static long endRequest() {
        return state().endRequest();
    }

    public static UUID peekParentCallId() {
        return state().peekParentCallId();
    }

    public static void pushCallId(UUID callId) {
        state().pushCallId(callId);
    }

    public static UUID popCallId() {
        return state().popCallId();
    }

    /** The request ID active on the calling thread ({@code 0} = none). */
    public static long currentRequestId() {
        return state().requestId;
    }

    /** The current traced-call nesting depth on the calling thread. */
    public static int currentDepth() {
        return state().depth;
    }

    /** Number of currently-open traced calls on the calling thread. */
    public static int callStackSize() {
        return state().callStack.size();
    }

    /** Clears the calling thread's state — test isolation helper. */
    public static void reset() {
        State s = state();
        s.requestId = 0L;
        s.depth = 0;
        s.callStack.clear();
    }

    /**
     * Run {@code body} with request state forced to (parentRequestId, depth=1)
     * and the call stack seeded with {@code parentCallId} (so the first traced
     * method on the worker thread sees the submitter's call as its parent),
     * restoring prior state on completion. Used by
     * Propagating{Runnable,Callable} to carry the request ID and call linkage
     * across thread boundaries.
     */
    public static void runScoped(long parentRequestId, UUID parentCallId, Runnable body) {
        State s = state();

        long savedRequestId = s.requestId;
        int savedDepth = s.depth;
        Deque<UUID> savedStack = s.callStack;

        s.requestId = parentRequestId;
        s.depth = 1;
        Deque<UUID> workerStack = new ArrayDeque<>();
        if (parentCallId != null) workerStack.push(parentCallId);
        s.callStack = workerStack;

        try {
            body.run();
        } finally {
            s.requestId = savedRequestId;
            s.depth = savedDepth;
            s.callStack = savedStack;
        }
    }

    /**
     * Callable counterpart of {@link #runScoped(long, UUID, Runnable)}.
     */
    public static <V> V callScoped(long parentRequestId, UUID parentCallId, Callable<V> body) throws Exception {
        State s = state();

        long savedRequestId = s.requestId;
        int savedDepth = s.depth;
        Deque<UUID> savedStack = s.callStack;

        s.requestId = parentRequestId;
        s.depth = 1;
        Deque<UUID> workerStack = new ArrayDeque<>();
        if (parentCallId != null) workerStack.push(parentCallId);
        s.callStack = workerStack;

        try {
            return body.call();
        } finally {
            s.requestId = savedRequestId;
            s.depth = savedDepth;
            s.callStack = savedStack;
        }
    }
}
