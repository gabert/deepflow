package com.github.gabert.deepflow.recorder;

import java.nio.charset.StandardCharsets;

public final class RecordWriter {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private RecordWriter() {}

    // --- Public API ---

    public static byte[] logEntry(String signature, String threadName, long timestamp,
                                  int callerLine, byte[] argsCbor) {
        byte[] start = methodStart(signature, threadName, timestamp, callerLine);
        byte[] args = arguments(argsCbor);
        return concat(start, args);
    }

    public static byte[] logExit(long timestamp, byte[] returnCbor, boolean isVoid) {
        byte[] ret = isVoid ? returnVoid() : returnValue(returnCbor);
        byte[] end = methodEnd(timestamp);
        return concat(ret, end);
    }

    public static byte[] logExitException(long timestamp, byte[] exceptionCbor) {
        byte[] exc = exception(exceptionCbor);
        byte[] end = methodEnd(timestamp);
        return concat(exc, end);
    }

    // --- logEntry internals ---

    private static byte[] methodStart(String signature, String threadName,
                                       long timestamp, int callerLine) {
        byte[] sigBytes = signature.getBytes(StandardCharsets.UTF_8);
        byte[] threadBytes = threadName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[RecordType.SIGNATURE_LENGTH_SIZE + sigBytes.length
                                + RecordType.THREAD_NAME_LENGTH_SIZE + threadBytes.length
                                + RecordType.TIMESTAMP_SIZE + RecordType.CALLER_LINE_SIZE];
        int pos = 0;
        pos = putShort(payload, pos, (short) sigBytes.length);
        System.arraycopy(sigBytes, 0, payload, pos, sigBytes.length);
        pos += sigBytes.length;
        pos = putShort(payload, pos, (short) threadBytes.length);
        System.arraycopy(threadBytes, 0, payload, pos, threadBytes.length);
        pos += threadBytes.length;
        pos = putLong(payload, pos, timestamp);
        putInt(payload, pos, callerLine);
        return frame(RecordType.METHOD_START, payload);
    }

    private static byte[] arguments(byte[] argsCbor) {
        return frame(RecordType.ARGUMENTS, argsCbor);
    }

    // --- logExit internals ---

    private static byte[] returnValue(byte[] valueCbor) {
        return frame(RecordType.RETURN, valueCbor);
    }

    private static byte[] returnVoid() {
        return frame(RecordType.RETURN, EMPTY_PAYLOAD);
    }

    private static byte[] exception(byte[] exceptionCbor) {
        return frame(RecordType.EXCEPTION, exceptionCbor);
    }

    private static byte[] methodEnd(long timestamp) {
        byte[] payload = new byte[RecordType.TIMESTAMP_SIZE];
        putLong(payload, 0, timestamp);
        return frame(RecordType.METHOD_END, payload);
    }

    // --- Shared utilities ---

    private static byte[] frame(byte type, byte[] payload) {
        byte[] frame = new byte[RecordType.HEADER_SIZE + payload.length];
        frame[0] = type;
        putInt(frame, 1, payload.length);
        System.arraycopy(payload, 0, frame, RecordType.HEADER_SIZE, payload.length);
        return frame;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static int putShort(byte[] buf, int pos, short value) {
        buf[pos]     = (byte) (value >>> 8);
        buf[pos + 1] = (byte) value;
        return pos + 2;
    }

    private static int putInt(byte[] buf, int pos, int value) {
        buf[pos]     = (byte) (value >>> 24);
        buf[pos + 1] = (byte) (value >>> 16);
        buf[pos + 2] = (byte) (value >>> 8);
        buf[pos + 3] = (byte) value;
        return pos + 4;
    }

    private static int putLong(byte[] buf, int pos, long value) {
        buf[pos]     = (byte) (value >>> 56);
        buf[pos + 1] = (byte) (value >>> 48);
        buf[pos + 2] = (byte) (value >>> 40);
        buf[pos + 3] = (byte) (value >>> 32);
        buf[pos + 4] = (byte) (value >>> 24);
        buf[pos + 5] = (byte) (value >>> 16);
        buf[pos + 6] = (byte) (value >>> 8);
        buf[pos + 7] = (byte) value;
        return pos + 8;
    }
}