package com.github.gabert.deepflow.recorder.record;

/**
 * Method-exit arguments — the same arguments captured again at exit time so
 * the formatter can detect mutations during the call. CBOR-encoded.
 */
public record ArgumentsExitRecord(byte[] cbor) implements TraceRecord {

    public static final byte TYPE = RecordType.ARGUMENTS_EXIT;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        return cbor;
    }

    public static ArgumentsExitRecord parse(byte[] payload) {
        return new ArgumentsExitRecord(payload);
    }
}
