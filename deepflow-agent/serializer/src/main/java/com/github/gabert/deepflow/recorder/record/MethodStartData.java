package com.github.gabert.deepflow.recorder.record;

public final class MethodStartData {
    public final String signature;
    public final String threadName;
    public final long timestamp;
    public final int callerLine;
    public final int depth;

    public MethodStartData(String signature, String threadName, long timestamp, int callerLine, int depth) {
        this.signature = signature;
        this.threadName = threadName;
        this.timestamp = timestamp;
        this.callerLine = callerLine;
        this.depth = depth;
    }
}
