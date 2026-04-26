package com.github.gabert.deepflow.agent;

import java.util.concurrent.Callable;

public class PropagatingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final long parentRequestId;

    public PropagatingCallable(Callable<V> delegate, long parentRequestId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
    }

    @Override
    public V call() throws Exception {
        long[] requestIdHolder = RequestContext.CURRENT_REQUEST_ID.get();
        int[] depthHolder = RequestContext.DEPTH.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;

        try {
            return delegate.call();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
        }
    }
}
