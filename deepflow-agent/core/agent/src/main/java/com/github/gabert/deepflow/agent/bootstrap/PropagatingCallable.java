package com.github.gabert.deepflow.agent.bootstrap;

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
        return RequestContext.callScoped(parentRequestId, delegate);
    }
}
