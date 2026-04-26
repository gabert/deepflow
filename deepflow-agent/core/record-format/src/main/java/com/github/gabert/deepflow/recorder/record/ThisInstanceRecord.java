package com.github.gabert.deepflow.recorder.record;

/**
 * The full (CBOR-encoded) {@code this} object for an instance method,
 * captured when {@code expand_this=true}. Use {@link ThisInstanceRefRecord}
 * for the lighter ref-only mode.
 */
public record ThisInstanceRecord(byte[] cbor) implements TraceRecord {

    public static final byte TYPE = RecordType.THIS_INSTANCE;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        return cbor;
    }

    public static ThisInstanceRecord parse(byte[] payload) {
        return new ThisInstanceRecord(payload);
    }
}
