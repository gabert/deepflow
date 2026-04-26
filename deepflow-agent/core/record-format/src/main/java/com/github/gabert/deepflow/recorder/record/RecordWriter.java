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

    private RecordWriter() {}

    // --- Composite: full method entry (start + this + arguments) ---

    public static byte[] logEntry(String sessionId, String signature, String threadName,
                                  long timestamp, int callerLine,
                                  long requestId,
                                  byte[] thisInstanceCbor, byte[] argsCbor) {
        return BinaryUtil.concat(
                logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId),
                thisInstanceCbor != null ? thisInstance(thisInstanceCbor) : null,
                arguments(argsCbor));
    }

    public static byte[] logEntryWithThisRef(String sessionId, String signature, String threadName,
                                             long timestamp, int callerLine,
                                             long requestId,
                                             long thisInstanceId, byte[] argsCbor) {
        return BinaryUtil.concat(
                logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, requestId),
                thisInstanceRef(thisInstanceId),
                arguments(argsCbor));
    }

    // --- Composite: full method exit (end + return) ---

    public static byte[] logExit(String sessionId, String threadName, long timestamp,
                                 long requestId, byte[] returnCbor, boolean isVoid) {
        return BinaryUtil.concat(
                methodEnd(sessionId, threadName, timestamp, requestId),
                isVoid ? returnVoid() : returnValue(returnCbor));
    }

    public static byte[] logExitException(String sessionId, String threadName,
                                          long timestamp, long requestId,
                                          byte[] exceptionCbor) {
        return BinaryUtil.concat(
                methodEnd(sessionId, threadName, timestamp, requestId),
                exception(exceptionCbor));
    }

    public static byte[] logExitWithArgs(String sessionId, String threadName, long timestamp,
                                         long requestId, byte[] returnCbor, boolean isVoid,
                                         byte[] argsCbor) {
        return BinaryUtil.concat(
                logExit(sessionId, threadName, timestamp, requestId, returnCbor, isVoid),
                argumentsExit(argsCbor));
    }

    public static byte[] logExitExceptionWithArgs(String sessionId, String threadName,
                                                   long timestamp, long requestId,
                                                   byte[] exceptionCbor, byte[] argsCbor) {
        return BinaryUtil.concat(
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

}
