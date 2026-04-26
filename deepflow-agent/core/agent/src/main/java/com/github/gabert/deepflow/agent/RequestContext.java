package com.github.gabert.deepflow.agent;

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
}
