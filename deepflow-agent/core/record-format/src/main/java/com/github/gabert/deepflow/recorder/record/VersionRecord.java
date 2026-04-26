package com.github.gabert.deepflow.recorder.record;

/**
 * Wire-format version marker. Written once at the start of each per-thread
 * stream so consumers know which payload schema to expect.
 *
 * <p>Payload layout: {@code [major:short][minor:short]}.</p>
 */
public record VersionRecord(short major, short minor) implements TraceRecord {

    public static final byte TYPE = RecordType.VERSION;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] payload = new byte[Short.BYTES * 2];
        BinaryUtil.putShort(payload, 0, major);
        BinaryUtil.putShort(payload, 2, minor);
        return payload;
    }

    public static VersionRecord parse(byte[] payload) {
        short major = (short) BinaryUtil.getShort(payload, 0);
        short minor = (short) BinaryUtil.getShort(payload, 2);
        return new VersionRecord(major, minor);
    }

    /** The version the agent currently emits. */
    public static VersionRecord current() {
        return new VersionRecord(RecordType.VERSION_MAJOR, RecordType.VERSION_MINOR);
    }
}
