package com.github.gabert.deepflow.recorder.record;

/**
 * Method return value, CBOR-encoded. A void return is encoded as a
 * zero-length payload — {@link #isVoid()} reports which case this is.
 */
public record ReturnRecord(byte[] cbor) implements TraceRecord {

    public static final byte TYPE = RecordType.RETURN;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        return cbor != null ? cbor : new byte[0];
    }

    public boolean isVoid() {
        return cbor == null || cbor.length == 0;
    }

    public static ReturnRecord parse(byte[] payload) {
        return new ReturnRecord(payload);
    }

    public static ReturnRecord ofVoid() {
        return new ReturnRecord(new byte[0]);
    }
}
