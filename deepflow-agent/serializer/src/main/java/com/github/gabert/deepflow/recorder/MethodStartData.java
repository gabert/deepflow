package com.github.gabert.deepflow.recorder;

public final class MethodStartData {
    public final String signature;
    public final long timestamp;
    public final int callerLine;

    public MethodStartData(String signature, long timestamp, int callerLine) {
        this.signature = signature;
        this.timestamp = timestamp;
        this.callerLine = callerLine;
    }
}