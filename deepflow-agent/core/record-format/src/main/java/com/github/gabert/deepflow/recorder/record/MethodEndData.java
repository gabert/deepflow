package com.github.gabert.deepflow.recorder.record;

public final class MethodEndData {
    public final String sessionId;
    public final long timestamp;
    public final String threadName;
    public final long requestId;

    public MethodEndData(String sessionId, long timestamp, String threadName, long requestId) {
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.threadName = threadName;
        this.requestId = requestId;
    }
}
