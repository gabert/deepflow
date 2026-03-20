package com.github.gabert.deepflow.recorder.record;

public final class TraceRecord {
    private final byte type;
    private final byte[] payload;

    public TraceRecord(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public byte type() {
        return type;
    }

    public byte[] payload() {
        return payload;
    }

    @Override
    public String toString() {
        return "TraceRecord{type=0x" + String.format("%02x", type)
                + ", payloadLength=" + payload.length + "}";
    }
}
