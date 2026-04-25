package com.github.gabert.deepflow.agent;

class PropagatingRunnable implements Runnable {
    private final Runnable delegate;
    private final long parentRequestId;

    PropagatingRunnable(Runnable delegate, long parentRequestId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
    }

    @Override
    public void run() {
        long[] requestIdHolder = DeepFlowAdvice.CURRENT_REQUEST_ID.get();
        int[] depthHolder = DeepFlowAdvice.DEPTH.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;

        try {
            delegate.run();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
        }
    }
}
