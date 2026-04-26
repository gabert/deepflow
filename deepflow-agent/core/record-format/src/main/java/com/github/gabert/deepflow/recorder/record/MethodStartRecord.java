package com.github.gabert.deepflow.recorder.record;

import java.nio.charset.StandardCharsets;

/**
 * Method-entry record carrying the call's structural metadata.
 *
 * <p>Payload layout (UTF-8 strings prefixed by short lengths):
 * {@code [sidLen:short][sid][sigLen:short][sig][tnLen:short][tn]
 *        [timestamp:long][callerLine:int][requestId:long]}.</p>
 *
 * <p>{@code sessionId} may be null; encoded as zero-length on the wire.</p>
 */
public record MethodStartRecord(
        String sessionId,
        String signature,
        String threadName,
        long timestamp,
        int callerLine,
        long requestId
) implements TraceRecord {

    public static final byte TYPE = RecordType.METHOD_START;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] sidBytes = sessionId != null ? sessionId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] sigBytes = signature.getBytes(StandardCharsets.UTF_8);
        byte[] tnBytes = threadName.getBytes(StandardCharsets.UTF_8);

        byte[] payload = new byte[
                RecordType.SESSION_ID_LENGTH_SIZE + sidBytes.length
                        + RecordType.SIGNATURE_LENGTH_SIZE + sigBytes.length
                        + RecordType.THREAD_NAME_LENGTH_SIZE + tnBytes.length
                        + RecordType.TIMESTAMP_SIZE + RecordType.CALLER_LINE_SIZE
                        + RecordType.REQUEST_ID_SIZE];
        int pos = 0;
        pos = BinaryUtil.putShort(payload, pos, (short) sidBytes.length);
        System.arraycopy(sidBytes, 0, payload, pos, sidBytes.length);
        pos += sidBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) sigBytes.length);
        System.arraycopy(sigBytes, 0, payload, pos, sigBytes.length);
        pos += sigBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) tnBytes.length);
        System.arraycopy(tnBytes, 0, payload, pos, tnBytes.length);
        pos += tnBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        pos = BinaryUtil.putInt(payload, pos, callerLine);
        BinaryUtil.putLong(payload, pos, requestId);
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
        return new MethodStartRecord(sessionId, signature, threadName, timestamp, callerLine, requestId);
    }
}
