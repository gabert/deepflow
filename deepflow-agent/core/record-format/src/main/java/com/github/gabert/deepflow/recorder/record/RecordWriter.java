package com.github.gabert.deepflow.recorder.record;

import java.nio.charset.StandardCharsets;

public final class RecordWriter {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];
    private static final byte[] EMPTY_SESSION_ID = new byte[0];

    private RecordWriter() {}

    // --- Public API ---

    public static byte[] logEntry(String sessionId, String signature, String threadName,
                                  long timestamp, int callerLine,
                                  long requestId,
                                  byte[] thisInstanceCbor, byte[] argsCbor) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        byte[] start = methodStart(sessionIdBytes, signature, threadName, timestamp, callerLine, requestId);
        byte[] thisInst = thisInstanceCbor != null ? thisInstance(thisInstanceCbor) : EMPTY_PAYLOAD;
        byte[] args = arguments(argsCbor);
        return concat(start, concat(thisInst, args));
    }

    public static byte[] logEntryWithThisRef(String sessionId, String signature, String threadName,
                                             long timestamp, int callerLine,
                                             long requestId,
                                             long thisInstanceId, byte[] argsCbor) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        byte[] start = methodStart(sessionIdBytes, signature, threadName, timestamp, callerLine, requestId);
        byte[] thisRef = thisInstanceRef(thisInstanceId);
        byte[] args = arguments(argsCbor);
        return concat(start, concat(thisRef, args));
    }

    public static byte[] logExit(String sessionId, String threadName, long timestamp,
                                 long requestId, byte[] returnCbor, boolean isVoid) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        byte[] end = methodEnd(sessionIdBytes, threadName, timestamp, requestId);
        byte[] ret = isVoid ? returnVoid() : returnValue(returnCbor);
        return concat(end, ret);
    }

    public static byte[] logExitException(String sessionId, String threadName,
                                          long timestamp, long requestId,
                                          byte[] exceptionCbor) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        byte[] end = methodEnd(sessionIdBytes, threadName, timestamp, requestId);
        byte[] exc = exception(exceptionCbor);
        return concat(end, exc);
    }

    public static byte[] logEntrySimple(String sessionId, String signature, String threadName,
                                        long timestamp, int callerLine,
                                        long requestId) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        return methodStart(sessionIdBytes, signature, threadName, timestamp, callerLine, requestId);
    }

    public static byte[] logExitWithArgs(String sessionId, String threadName, long timestamp,
                                         long requestId, byte[] returnCbor, boolean isVoid,
                                         byte[] argsCbor) {
        byte[] base = logExit(sessionId, threadName, timestamp, requestId, returnCbor, isVoid);
        byte[] exitArgs = argumentsExit(argsCbor);
        return concat(base, exitArgs);
    }

    public static byte[] logExitExceptionWithArgs(String sessionId, String threadName,
                                                   long timestamp, long requestId,
                                                   byte[] exceptionCbor, byte[] argsCbor) {
        byte[] base = logExitException(sessionId, threadName, timestamp, requestId, exceptionCbor);
        byte[] exitArgs = argumentsExit(argsCbor);
        return concat(base, exitArgs);
    }

    public static byte[] logExitSimple(String sessionId, String threadName, long timestamp,
                                       long requestId) {
        byte[] sessionIdBytes = encodeSessionId(sessionId);
        return methodEnd(sessionIdBytes, threadName, timestamp, requestId);
    }

    public static byte[] version(short major, short minor) {
        byte[] payload = new byte[Short.BYTES + Short.BYTES];
        int pos = 0;
        pos = BinaryUtil.putShort(payload, pos, major);
        BinaryUtil.putShort(payload, pos, minor);
        return frame(RecordType.VERSION, payload);
    }

    public static byte[] version() {
        return version(RecordType.VERSION_MAJOR, RecordType.VERSION_MINOR);
    }

    // --- logEntry internals ---

    private static byte[] methodStart(byte[] sessionIdBytes, String signature, String threadName,
                                       long timestamp, int callerLine,
                                       long requestId) {
        byte[] sigBytes = signature.getBytes(StandardCharsets.UTF_8);
        byte[] threadBytes = threadName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[RecordType.SESSION_ID_LENGTH_SIZE + sessionIdBytes.length
                                + RecordType.SIGNATURE_LENGTH_SIZE + sigBytes.length
                                + RecordType.THREAD_NAME_LENGTH_SIZE + threadBytes.length
                                + RecordType.TIMESTAMP_SIZE + RecordType.CALLER_LINE_SIZE
                                + RecordType.REQUEST_ID_SIZE];
        int pos = 0;
        pos = BinaryUtil.putShort(payload, pos, (short) sessionIdBytes.length);
        System.arraycopy(sessionIdBytes, 0, payload, pos, sessionIdBytes.length);
        pos += sessionIdBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) sigBytes.length);
        System.arraycopy(sigBytes, 0, payload, pos, sigBytes.length);
        pos += sigBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) threadBytes.length);
        System.arraycopy(threadBytes, 0, payload, pos, threadBytes.length);
        pos += threadBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        pos = BinaryUtil.putInt(payload, pos, callerLine);
        BinaryUtil.putLong(payload, pos, requestId);
        return frame(RecordType.METHOD_START, payload);
    }

    public static byte[] thisInstance(byte[] thisCbor) {
        return frame(RecordType.THIS_INSTANCE, thisCbor);
    }

    public static byte[] thisInstanceRef(long objectId) {
        byte[] payload = new byte[Long.BYTES];
        BinaryUtil.putLong(payload, 0, objectId);
        return frame(RecordType.THIS_INSTANCE_REF, payload);
    }

    public static byte[] arguments(byte[] argsCbor) {
        return frame(RecordType.ARGUMENTS, argsCbor);
    }

    public static byte[] argumentsExit(byte[] argsCbor) {
        return frame(RecordType.ARGUMENTS_EXIT, argsCbor);
    }

    // --- logExit internals ---

    public static byte[] returnValue(byte[] valueCbor) {
        return frame(RecordType.RETURN, valueCbor);
    }

    public static byte[] returnVoid() {
        return frame(RecordType.RETURN, EMPTY_PAYLOAD);
    }

    public static byte[] exception(byte[] exceptionCbor) {
        return frame(RecordType.EXCEPTION, exceptionCbor);
    }

    public static byte[] methodEnd(String sessionId, String threadName, long timestamp,
                                   long requestId) {
        return methodEnd(encodeSessionId(sessionId), threadName, timestamp, requestId);
    }

    private static byte[] methodEnd(byte[] sessionIdBytes, String threadName, long timestamp,
                                    long requestId) {
        byte[] threadBytes = threadName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[RecordType.SESSION_ID_LENGTH_SIZE + sessionIdBytes.length
                                + RecordType.THREAD_NAME_LENGTH_SIZE + threadBytes.length
                                + RecordType.TIMESTAMP_SIZE
                                + RecordType.REQUEST_ID_SIZE];
        int pos = 0;
        pos = BinaryUtil.putShort(payload, pos, (short) sessionIdBytes.length);
        System.arraycopy(sessionIdBytes, 0, payload, pos, sessionIdBytes.length);
        pos += sessionIdBytes.length;
        pos = BinaryUtil.putShort(payload, pos, (short) threadBytes.length);
        System.arraycopy(threadBytes, 0, payload, pos, threadBytes.length);
        pos += threadBytes.length;
        pos = BinaryUtil.putLong(payload, pos, timestamp);
        BinaryUtil.putLong(payload, pos, requestId);
        return frame(RecordType.METHOD_END, payload);
    }

    // --- Shared utilities ---

    private static byte[] encodeSessionId(String sessionId) {
        return sessionId != null ? sessionId.getBytes(StandardCharsets.UTF_8) : EMPTY_SESSION_ID;
    }

    private static byte[] frame(byte type, byte[] payload) {
        byte[] frame = new byte[RecordType.HEADER_SIZE + payload.length];
        frame[0] = type;
        BinaryUtil.putInt(frame, 1, payload.length);
        System.arraycopy(payload, 0, frame, RecordType.HEADER_SIZE, payload.length);
        return frame;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
