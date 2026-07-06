package com.github.gabert.arachna.trace.recorder.record;

import java.util.UUID;

/**
 * Convenience facade for writing records to bytes. Each method builds the
 * matching {@link TraceRecord}, calls {@link TraceRecord#toFrame()}, and
 * concatenates frames where the operation is composite (e.g. {@code logEntry}
 * = method-start + this-instance + arguments).
 *
 * <p>The marshaling itself lives on the record classes; this facade just
 * provides ergonomic call sites for the agent's hot path and existing tests.</p>
 */
public final class RecordWriter {

    private RecordWriter() {}

    // --- Composite: single-allocation frame concatenation ---

    /**
     * Marshals the given records into one contiguous frame sequence with a
     * single output allocation, skipping {@code null} entries. Byte-identical
     * to concatenating each record's {@link TraceRecord#toFrame()}, but each
     * payload is copied exactly once (into the output) instead of twice
     * (into its own frame, then into the concatenation) — the agent's hot
     * path uses this for composite entry/exit records.
     */
    public static byte[] frames(TraceRecord... records) {
        byte[][] payloads = new byte[records.length][];
        int total = 0;
        for (int i = 0; i < records.length; i++) {
            if (records[i] == null) continue;
            payloads[i] = records[i].payloadBytes();
            total += RecordType.HEADER_SIZE + payloads[i].length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (int i = 0; i < records.length; i++) {
            byte[] payload = payloads[i];
            if (payload == null) continue;
            out[pos] = records[i].typeByte();
            pos = BinaryUtil.putInt(out, pos + 1, payload.length);
            System.arraycopy(payload, 0, out, pos, payload.length);
            pos += payload.length;
        }
        return out;
    }

    // --- Composite: full method entry (start + this + arguments) ---

    public static byte[] logEntry(String sessionId, String signature, String threadName,
                                  long timestamp, int callerLine,
                                  long requestId,
                                  UUID callId, UUID parentCallId,
                                  byte[] thisInstanceCbor, byte[] argsCbor) {
        return BinaryUtil.concat(
                logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId,
                        callId, parentCallId),
                thisInstanceCbor != null ? thisInstance(thisInstanceCbor) : null,
                arguments(argsCbor));
    }

    public static byte[] logEntryWithThisRef(String sessionId, String signature, String threadName,
                                             long timestamp, int callerLine,
                                             long requestId,
                                             UUID callId, UUID parentCallId,
                                             long thisInstanceId, byte[] argsCbor) {
        return BinaryUtil.concat(
                logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId,
                        callId, parentCallId),
                thisInstanceRef(thisInstanceId),
                arguments(argsCbor));
    }

    // --- Composite: full method exit (end + return) ---

    public static byte[] logExit(String sessionId, String threadName, long timestamp,
                                 long requestId, UUID callId,
                                 byte[] returnCbor, boolean isVoid) {
        return BinaryUtil.concat(
                methodEnd(sessionId, threadName, timestamp, requestId, callId),
                isVoid ? returnVoid() : returnValue(returnCbor));
    }

    public static byte[] logExitException(String sessionId, String threadName,
                                          long timestamp, long requestId, UUID callId,
                                          byte[] exceptionCbor) {
        return BinaryUtil.concat(
                methodEnd(sessionId, threadName, timestamp, requestId, callId),
                exception(exceptionCbor));
    }

    // --- Single records ---

    public static byte[] logEntrySimple(String sessionId, String signature, String threadName,
                                        long timestamp, int callerLine,
                                        long requestId,
                                        UUID callId, UUID parentCallId) {
        return new MethodStartRecord(sessionId, signature, threadName, timestamp, callerLine, requestId,
                callId, parentCallId).toFrame();
    }

    public static byte[] methodEnd(String sessionId, String threadName, long timestamp,
                                   long requestId, UUID callId) {
        return new MethodEndRecord(sessionId, threadName, timestamp, requestId, callId).toFrame();
    }

    public static byte[] thisInstance(byte[] thisCbor) {
        return new ThisInstanceRecord(thisCbor).toFrame();
    }

    public static byte[] thisInstanceRef(long objectId) {
        return new ThisInstanceRefRecord(objectId).toFrame();
    }

    public static byte[] arguments(byte[] argsCbor) {
        return new ArgumentsRecord(argsCbor).toFrame();
    }

    public static byte[] argumentsExit(byte[] argsCbor) {
        return new ArgumentsExitRecord(argsCbor).toFrame();
    }

    public static byte[] returnValue(byte[] valueCbor) {
        return new ReturnRecord(valueCbor).toFrame();
    }

    public static byte[] returnVoid() {
        return ReturnRecord.ofVoid().toFrame();
    }

    public static byte[] exception(byte[] exceptionCbor) {
        return new ExceptionRecord(exceptionCbor).toFrame();
    }

    public static byte[] version(short major, short minor) {
        return new VersionRecord(major, minor).toFrame();
    }

    public static byte[] version() {
        return VersionRecord.current().toFrame();
    }

    public static byte[] sequence(UUID callId, long seq) {
        return new SequenceRecord(callId, seq).toFrame();
    }

}
