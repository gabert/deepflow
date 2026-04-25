package com.github.gabert.deepflow.recorder.record;

public final class MethodStartData {
    public final String sessionId;
    public final String signature;
    public final String threadName;
    public final long timestamp;
    public final int callerLine;
    public final long callId;

    public MethodStartData(String sessionId, String signature, String threadName,
                           long timestamp, int callerLine,
                           long callId) {
        this.sessionId = sessionId;
        this.signature = signature;
        this.threadName = threadName;
        this.timestamp = timestamp;
        this.callerLine = callerLine;
        this.callId = callId;
    }
}
