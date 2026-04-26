package com.github.gabert.deepflow.recorder.record;

import java.nio.charset.StandardCharsets;

/**
 * Method-exit record. Pairs with the preceding {@link MethodStartRecord} via
 * {@code requestId}.
 *
 * <p>Payload layout:
 * {@code [sidLen:short][sid][tnLen:short][tn][timestamp:long][requestId:long]}.</p>
 *
 * <p>{@code sessionId} may be null; encoded as zero-length on the wire.</p>
 */
public record MethodEndRecord(
        String sessionId,
        String threadName,
        long timestamp,
        long requestId
) implements TraceRecord {

    public static final byte TYPE = RecordType.METHOD_END;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] sidBytes = sessionId != null ? sessionId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] tnBytes = threadName.getBytes(StandardCharsets.UTF_8);

        byte[] payload = new byte[
                RecordType.SESSION_ID_LENGTH_SIZE + sidBytes.length
                        + RecordType.THREAD_NAME_LENGTH_SIZE + tnBytes.length
                        + RecordType.TIMESTAMP_SIZE
                        + RecordType.REQUEST_ID_SIZE];
        int pos = 0;
        pos = BinaryUtil.putShort(payload, pos, (short) sidBytes.length);
        System.arraycopy(sidBytes, 0, payload, pos, sidBytes.length);
        pos += sidBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) tnBytes.length);
        System.arraycopy(tnBytes, 0, payload, pos, tnBytes.length);
        pos += tnBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        BinaryUtil.putLong(payload, pos, requestId);
        return payload;
    }

    public static MethodEndRecord parse(byte[] payload) {
        int pos = 0;
        int sidLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SESSION_ID_LENGTH_SIZE;
        String sessionId = sidLen > 0 ? new String(payload, pos, sidLen, StandardCharsets.UTF_8) : null;
        pos += sidLen;
        int tnLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, tnLen, StandardCharsets.UTF_8);
        pos += tnLen;
        long timestamp = BinaryUtil.getLong(payload, pos);
        pos += RecordType.TIMESTAMP_SIZE;
        long requestId = BinaryUtil.getLong(payload, pos);
        return new MethodEndRecord(sessionId, threadName, timestamp, requestId);
    }
}
