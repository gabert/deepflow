package com.github.gabert.deepflow.agent.bootstrap;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local state for request ID propagation.
 *
 * <p>This class is injected into the bootstrap classloader so that advice
 * inlined into JDK classes (ThreadPoolExecutor, ForkJoinPool) can access
 * the same request ID and depth state as advice running in application classes.</p>
 */
public class RequestContext {
    public static final AtomicLong REQUEST_COUNTER = new AtomicLong(0);
    public static final ThreadLocal<long[]> CURRENT_REQUEST_ID =
            ThreadLocal.withInitial(() -> new long[]{0L});
    public static final ThreadLocal<int[]> DEPTH =
            ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Begin a request: at depth 0 assign a fresh request ID, then increment depth.
     * Returns the active request ID.
     */
    public static long beginRequest() {
        int[] depthHolder = DEPTH.get();
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        if (depthHolder[0] == 0) {
            requestIdHolder[0] = REQUEST_COUNTER.incrementAndGet();
        }
        depthHolder[0]++;
        return requestIdHolder[0];
    }

    /**
     * End a request: decrement depth (clamped at 0). Returns the request ID
     * that was active for this exit so callers can stamp it on records.
     */
    public static long endRequest() {
        int[] depthHolder = DEPTH.get();
        long requestId = CURRENT_REQUEST_ID.get()[0];
        if (depthHolder[0] > 0) {
            depthHolder[0]--;
        }
        return requestId;
    }

    /**
     * Run {@code body} with request state forced to (parentRequestId, depth=1),
     * restoring prior state on completion. Used by Propagating{Runnable,Callable}
     * to carry request ID across thread boundaries.
     */
    public static void runScoped(long parentRequestId, Runnable body) {
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        int[] depthHolder = DEPTH.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;

        try {
            body.run();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
        }
    }

    /**
     * Callable counterpart of {@link #runScoped(long, Runnable)}.
     */
    public static <V> V callScoped(long parentRequestId, Callable<V> body) throws Exception {
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        int[] depthHolder = DEPTH.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;

        try {
            return body.call();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
        }
    }
}
