package com.github.gabert.deepflow.recorder.record;

/**
 * Method-entry arguments, captured as a CBOR-encoded array. The bytes are
 * passed through opaquely on the wire; CBOR decoding happens at render time.
 */
public record ArgumentsRecord(byte[] cbor) implements TraceRecord {

    public static final byte TYPE = RecordType.ARGUMENTS;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        return cbor;
    }

    public static ArgumentsRecord parse(byte[] payload) {
        return new ArgumentsRecord(payload);
    }
}
