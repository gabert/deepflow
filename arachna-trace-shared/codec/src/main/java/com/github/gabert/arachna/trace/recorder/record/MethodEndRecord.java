package com.github.gabert.arachna.trace.recorder.record;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Method-exit record. Pairs with the preceding {@link MethodStartRecord}
 * via {@code callId}.
 *
 * <p>Payload layout:
 * {@code [sidLen:short][sid][tnLen:short][tn][timestamp:long][requestId:long]
 *        [callId:16]}.</p>
 *
 * <p>{@code sessionId} may be null; encoded as zero-length on the wire.
 * {@code callId} is required.</p>
 *
 * <p>Agent-run identity is carried at the transport layer (HTTP / Kafka
 * headers, file sidecar) — see {@link com.github.gabert.arachna.trace.codec.AgentRun}.
 * It is no longer in the record payload.</p>
 */
public record MethodEndRecord(
        String sessionId,
        String threadName,
        long timestamp,
        long requestId,
        UUID callId
) implements TraceRecord {

    public static final byte TYPE = RecordType.METHOD_END;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] sidBytes = sessionId != null ? sessionId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return payloadFrom(sidBytes, threadName.getBytes(StandardCharsets.UTF_8),
                timestamp, requestId, callId);
    }

    /**
     * Marshals the payload from pre-encoded UTF-8 strings — the agent's hot
     * path caches threadName/sessionId bytes and calls this directly (via
     * {@link RawFrame}) instead of re-encoding per call. Single source of
     * truth for the layout; {@link #payloadBytes()} delegates here. A null
     * sessionId is passed as a zero-length array.
     */
    public static byte[] payloadFrom(byte[] sidBytes, byte[] tnBytes,
                                     long timestamp, long requestId, UUID callId) {
        byte[] payload = new byte[
                RecordType.SESSION_ID_LENGTH_SIZE + sidBytes.length
                        + RecordType.THREAD_NAME_LENGTH_SIZE + tnBytes.length
                        + RecordType.TIMESTAMP_SIZE
                        + RecordType.REQUEST_ID_SIZE
                        + RecordType.UUID_SIZE];
        int pos = 0;
        pos = BinaryUtil.putUnsignedShort(payload, pos, sidBytes.length);
        System.arraycopy(sidBytes, 0, payload, pos, sidBytes.length);
        pos += sidBytes.length;
        pos = BinaryUtil.putUnsignedShort(payload, pos, tnBytes.length);
        System.arraycopy(tnBytes, 0, payload, pos, tnBytes.length);
        pos += tnBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        pos = BinaryUtil.putLong(payload, pos, requestId);
        BinaryUtil.putUuid(payload, pos, callId);
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
        pos += RecordType.REQUEST_ID_SIZE;
        UUID callId = BinaryUtil.getNullableUuid(payload, pos);
        return new MethodEndRecord(sessionId, threadName, timestamp, requestId, callId);
    }
}
