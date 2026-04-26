package com.github.gabert.deepflow.recorder.record;

/**
 * A stable identity reference for an instance method's {@code this}, used
 * when {@code expand_this=false}. The full object isn't serialized — just a
 * long ID that's unique for the lifetime of the agent process.
 *
 * <p>Payload layout: {@code [objectId:long]}.</p>
 */
public record ThisInstanceRefRecord(long objectId) implements TraceRecord {

    public static final byte TYPE = RecordType.THIS_INSTANCE_REF;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] payload = new byte[Long.BYTES];
        BinaryUtil.putLong(payload, 0, objectId);
        return payload;
    }

    public static ThisInstanceRefRecord parse(byte[] payload) {
        return new ThisInstanceRefRecord(BinaryUtil.getLong(payload, 0));
    }
}
