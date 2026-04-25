package com.github.gabert.deepflow.agent;

import java.util.concurrent.Callable;

class PropagatingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final long parentRequestId;

    PropagatingCallable(Callable<V> delegate, long parentRequestId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
    }

    @Override
    public V call() throws Exception {
        long[] requestIdHolder = DeepFlowAdvice.CURRENT_REQUEST_ID.get();
        int[] depthHolder = DeepFlowAdvice.DEPTH.get();

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
