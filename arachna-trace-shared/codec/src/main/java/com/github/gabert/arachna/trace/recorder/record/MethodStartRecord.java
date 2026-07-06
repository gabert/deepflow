package com.github.gabert.arachna.trace.recorder.record;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Method-entry record carrying the call's structural metadata and identity.
 *
 * <p>Payload layout (UTF-8 strings prefixed by short lengths):
 * {@code [sidLen:short][sid][sigLen:short][sig][tnLen:short][tn]
 *        [timestamp:long][callerLine:int][requestId:long]
 *        [callId:16][parentCallId:16]}.</p>
 *
 * <p>{@code sessionId} may be null; encoded as zero-length on the wire.
 * {@code parentCallId} may be null (top of a request); encoded as the
 * all-zero UUID sentinel. {@code callId} is required.</p>
 *
 * <p>Agent-run identity is carried at the transport layer (HTTP / Kafka
 * headers, file sidecar) — see {@link com.github.gabert.arachna.trace.codec.AgentRun}.
 * It is no longer in the record payload.</p>
 */
public record MethodStartRecord(
        String sessionId,
        String signature,
        String threadName,
        long timestamp,
        int callerLine,
        long requestId,
        UUID callId,
        UUID parentCallId
) implements TraceRecord {

    public static final byte TYPE = RecordType.METHOD_START;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] sidBytes = sessionId != null ? sessionId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return payloadFrom(sidBytes, signature.getBytes(StandardCharsets.UTF_8),
                threadName.getBytes(StandardCharsets.UTF_8),
                timestamp, callerLine, requestId, callId, parentCallId);
    }

    /**
     * Marshals the payload from pre-encoded UTF-8 strings — the agent's hot
     * path caches signature/threadName/sessionId bytes and calls this
     * directly (via {@link RawFrame}) instead of re-encoding per call.
     * Single source of truth for the layout; {@link #payloadBytes()}
     * delegates here. A null sessionId is passed as a zero-length array.
     */
    public static byte[] payloadFrom(byte[] sidBytes, byte[] sigBytes, byte[] tnBytes,
                                     long timestamp, int callerLine, long requestId,
                                     UUID callId, UUID parentCallId) {
        byte[] payload = new byte[
                RecordType.SESSION_ID_LENGTH_SIZE + sidBytes.length
                        + RecordType.SIGNATURE_LENGTH_SIZE + sigBytes.length
                        + RecordType.THREAD_NAME_LENGTH_SIZE + tnBytes.length
                        + RecordType.TIMESTAMP_SIZE + RecordType.CALLER_LINE_SIZE
                        + RecordType.REQUEST_ID_SIZE
                        + RecordType.UUID_SIZE * 2];
        int pos = 0;
        pos = BinaryUtil.putUnsignedShort(payload, pos, sidBytes.length);
        System.arraycopy(sidBytes, 0, payload, pos, sidBytes.length);
        pos += sidBytes.length;
        pos = BinaryUtil.putUnsignedShort(payload, pos, sigBytes.length);
        System.arraycopy(sigBytes, 0, payload, pos, sigBytes.length);
        pos += sigBytes.length;
        pos = BinaryUtil.putUnsignedShort(payload, pos, tnBytes.length);
        System.arraycopy(tnBytes, 0, payload, pos, tnBytes.length);
        pos += tnBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        pos = BinaryUtil.putInt(payload, pos, callerLine);
        pos = BinaryUtil.putLong(payload, pos, requestId);
        pos = BinaryUtil.putUuid(payload, pos, callId);
        BinaryUtil.putUuid(payload, pos, parentCallId);
        return payload;
    }

    public static MethodStartRecord parse(byte[] payload) {
        int pos = 0;
        int sidLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SESSION_ID_LENGTH_SIZE;
        String sessionId = sidLen > 0 ? new String(payload, pos, sidLen, StandardCharsets.UTF_8) : null;
        pos += sidLen;
        int sigLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SIGNATURE_LENGTH_SIZE;
        String signature = new String(payload, pos, sigLen, StandardCharsets.UTF_8);
        pos += sigLen;
        int tnLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, tnLen, StandardCharsets.UTF_8);
        pos += tnLen;
        long timestamp = BinaryUtil.getLong(payload, pos);
        pos += RecordType.TIMESTAMP_SIZE;
        int callerLine = BinaryUtil.getInt(payload, pos);
        pos += RecordType.CALLER_LINE_SIZE;
        long requestId = BinaryUtil.getLong(payload, pos);
        pos += RecordType.REQUEST_ID_SIZE;
        UUID callId = BinaryUtil.getNullableUuid(payload, pos);
        pos += RecordType.UUID_SIZE;
        UUID parentCallId = BinaryUtil.getNullableUuid(payload, pos);
        return new MethodStartRecord(sessionId, signature, threadName, timestamp, callerLine, requestId,
                callId, parentCallId);
    }
}
