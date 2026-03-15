package com.github.gabert.deepflow.recorder;

public final class MethodEndData {
    public final long timestamp;
    public final String threadName;

    public MethodEndData(long timestamp, String threadName) {
        this.timestamp = timestamp;
        this.threadName = threadName;
    }
}