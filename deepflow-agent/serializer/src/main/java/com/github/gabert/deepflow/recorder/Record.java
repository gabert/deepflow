package com.github.gabert.deepflow.recorder;

public final class Record {
    private final byte type;
    private final byte[] payload;

    public Record(byte type, byte[] payload) {
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
        return "Record{type=0x" + String.format("%02x", type)
                + ", payloadLength=" + payload.length + "}";
    }
}