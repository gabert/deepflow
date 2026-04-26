package com.github.gabert.deepflow.recorder.record;

/**
 * The thrown exception from a method exit, CBOR-encoded as a structured
 * map with {@code message} and {@code stacktrace} fields.
 */
public record ExceptionRecord(byte[] cbor) implements TraceRecord {

    public static final byte TYPE = RecordType.EXCEPTION;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        return cbor;
    }

    public static ExceptionRecord parse(byte[] payload) {
        return new ExceptionRecord(payload);
    }
}
