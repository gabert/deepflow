package com.github.gabert.deepflow.agent.bootstrap;

public class PropagatingRunnable implements Runnable {
    private final Runnable delegate;
    private final long parentRequestId;

    public PropagatingRunnable(Runnable delegate, long parentRequestId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
    }

    @Override
    public void run() {
        RequestContext.runScoped(parentRequestId, delegate);
    }
}
