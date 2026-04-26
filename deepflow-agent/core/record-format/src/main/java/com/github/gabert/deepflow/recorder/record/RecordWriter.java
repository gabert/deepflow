package com.github.gabert.deepflow.recorder.record;

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
    private static final byte[] EMPTY_FRAME = new byte[0];

    private RecordWriter() {}

    // --- Composite: full method entry (start + this + arguments) ---

    public static byte[] logEntry(String sessionId, String signature, String threadName,
                                  long timestamp, int callerLine,
                                  long requestId,
                                  byte[] thisInstanceCbor, byte[] argsCbor) {
        byte[] start = logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId);
        byte[] thisFrame = thisInstanceCbor != null ? thisInstance(thisInstanceCbor) : EMPTY_FRAME;
        byte[] args = arguments(argsCbor);
        return concat(start, concat(thisFrame, args));
    }

    public static byte[] logEntryWithThisRef(String sessionId, String signature, String threadName,
                                             long timestamp, int callerLine,
                                             long requestId,
                                             long thisInstanceId, byte[] argsCbor) {
        byte[] start = logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId);
        byte[] thisRef = thisInstanceRef(thisInstanceId);
        byte[] args = arguments(argsCbor);
        return concat(start, concat(thisRef, args));
    }

    // --- Composite: full method exit (end + return) ---

    public static byte[] logExit(String sessionId, String threadName, long timestamp,
                                 long requestId, byte[] returnCbor, boolean isVoid) {
        byte[] end = methodEnd(sessionId, threadName, timestamp, requestId);
        byte[] ret = isVoid ? returnVoid() : returnValue(returnCbor);
        return concat(end, ret);
    }

    public static byte[] logExitException(String sessionId, String threadName,
                                          long timestamp, long requestId,
                                          byte[] exceptionCbor) {
        byte[] end = methodEnd(sessionId, threadName, timestamp, requestId);
        byte[] exc = exception(exceptionCbor);
        return concat(end, exc);
    }

    public static byte[] logExitWithArgs(String sessionId, String threadName, long timestamp,
                                         long requestId, byte[] returnCbor, boolean isVoid,
                                         byte[] argsCbor) {
        return concat(
                logExit(sessionId, threadName, timestamp, requestId, returnCbor, isVoid),
                argumentsExit(argsCbor));
    }

    public static byte[] logExitExceptionWithArgs(String sessionId, String threadName,
                                                   long timestamp, long requestId,
                                                   byte[] exceptionCbor, byte[] argsCbor) {
        return concat(
                logExitException(sessionId, threadName, timestamp, requestId, exceptionCbor),
                argumentsExit(argsCbor));
    }

    // --- Single records ---

    public static byte[] logEntrySimple(String sessionId, String signature, String threadName,
                                        long timestamp, int callerLine,
                                        long requestId) {
        return new MethodStartRecord(sessionId, signature, threadName, timestamp, callerLine, requestId).toFrame();
    }

    public static byte[] logExitSimple(String sessionId, String threadName, long timestamp,
                                       long requestId) {
        return new MethodEndRecord(sessionId, threadName, timestamp, requestId).toFrame();
    }

    public static byte[] methodEnd(String sessionId, String threadName, long timestamp,
                                   long requestId) {
        return new MethodEndRecord(sessionId, threadName, timestamp, requestId).toFrame();
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

    // --- Helpers ---

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
