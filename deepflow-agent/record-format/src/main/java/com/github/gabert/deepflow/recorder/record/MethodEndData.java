package com.github.gabert.deepflow.recorder.record;

public final class MethodEndData {
    public final String sessionId;
    public final long timestamp;
    public final String threadName;

    public MethodEndData(String sessionId, long timestamp, String threadName) {
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.threadName = threadName;
    }
}
