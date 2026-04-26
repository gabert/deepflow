package com.github.gabert.deepflow.agent.recording;

import com.github.gabert.deepflow.agent.AgentConfig;
import com.github.gabert.deepflow.agent.bootstrap.RequestContext;
import com.github.gabert.deepflow.agent.spi.SpiBootstrap;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.record.RecordWriter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Owns the per-call recording logic: builds entry/exit byte records and
 * pushes them to the buffer. Constructed once at agent startup; the active
 * instance is held by {@code DeepFlowAdvice.RECORDER} and read by inlined
 * advice on every traced method invocation.
 *
 * <p>Flag fields (expandThis, serializeValues, emit*) are snapshotted from
 * the config in the constructor so the hot path does not pay a HashMap
 * lookup per call.</p>
 */
public class RequestRecorder {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private final RecordBuffer recordBuffer;
    private final ValueEncoder valueEncoder;
    private final SpiBootstrap spi;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final boolean emitTi;
    private final boolean emitAr;
    private final boolean emitReturnRecord;
    private final boolean emitAx;

    public RequestRecorder(RecordBuffer recordBuffer, AgentConfig config) {
        this.recordBuffer = recordBuffer;
        this.valueEncoder = new ValueEncoder(config.getMaxValueSize());
        this.spi = new SpiBootstrap(config);
        this.expandThis = config.isExpandThis();
        this.serializeValues = config.isSerializeValues();
        this.emitTi = config.shouldEmit("TI");
        this.emitAr = config.shouldEmit("AR");
        // RT and RE are written as a single byte-record (RT is the record-type
        // byte, RE is the optional payload). The renderer trims to whichever
        // tags are configured, so we only need to know whether either is wanted.
        this.emitReturnRecord = config.shouldEmit("RT") || config.shouldEmit("RE");
        this.emitAx = config.shouldEmit("AX");
    }

    public RecordBuffer getRecordBuffer() {
        return recordBuffer;
    }

    // --- Record entry ---

    public void recordEntry(Method method, Object self, Object[] allArguments) {
        if (recordBuffer == null) return;
        spi.initJpaProxyResolverOnce();
        try {
            String signature = MethodSignatureFormatter.format(method);
            String threadName = Thread.currentThread().getName();
            long timestamp = System.nanoTime();
            // Stack at this point: [recordEntry, target_method, caller_of_target, ...]
            // ByteBuddy inlines onEnter into target_method's bytecode, so the call
            // to recordEntry lives there at runtime. skip(2) walks past both frames
            // and lands on the actual caller of the traced method.
            int callerLine = STACK_WALKER
                    .walk(s -> s.skip(2).findFirst())
                    .map(StackWalker.StackFrame::getLineNumber)
                    .orElse(0);

            String sessionId = spi.getSessionIdResolver().resolve();

            long requestId = RequestContext.beginRequest();

            byte[] record;
            if (serializeValues) {
                Object selfForCapture = emitTi ? self : null;
                Object[] argsForCapture = emitAr ? allArguments : null;
                record = buildSerializedEntry(sessionId, signature, threadName, timestamp, callerLine,
                        requestId, selfForCapture, argsForCapture);
            } else {
                record = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine,
                        requestId);
            }

            recordBuffer.offer(record);
        } catch (Throwable t) {
            System.err.println("Error recording entry.");
            t.printStackTrace();
        }
    }

    // --- Record exit ---

    public void recordExit(Method method, Object returned, Throwable throwable,
                           Object[] allArguments) {
        if (recordBuffer == null) return;
        long requestId = RequestContext.endRequest();
        try {
            String threadName = Thread.currentThread().getName();
            long timestamp = System.nanoTime();

            String sessionId = spi.getSessionIdResolver().resolve();

            byte[] record;
            if (serializeValues) {
                byte[] exitArgsCbor = (emitAx && emitAr) ? valueEncoder.encode(allArguments) : null;

                byte[] returnRecord;
                if (!emitReturnRecord) {
                    returnRecord = RecordWriter.returnVoid();
                } else if (throwable != null) {
                    returnRecord = RecordWriter.exception(valueEncoder.encode(buildExceptionData(throwable)));
                } else {
                    boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                    returnRecord = isVoid
                            ? RecordWriter.returnVoid()
                            : RecordWriter.returnValue(valueEncoder.encode(returned));
                }

                byte[] endRecord = RecordWriter.methodEnd(sessionId, threadName, timestamp, requestId);
                byte[] exitArgsRecord = exitArgsCbor != null
                        ? RecordWriter.argumentsExit(exitArgsCbor)
                        : new byte[0];

                record = concatOptional(endRecord, returnRecord, exitArgsRecord);
            } else {
                record = RecordWriter.logExitSimple(sessionId, threadName, timestamp, requestId);
            }
            recordBuffer.offer(record);
        } catch (Throwable t) {
            System.err.println("Error recording exit.");
            t.printStackTrace();
        }
    }

    // --- Private: entry record building ---

    private byte[] buildSerializedEntry(String sessionId, String signature, String threadName,
                                         long timestamp, int callerLine,
                                         long requestId,
                                         Object self, Object[] allArguments) throws IOException {
        byte[] startRecord = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine,
                requestId);

        byte[] thisRecord = null;
        if (self != null) {
            if (expandThis) {
                thisRecord = RecordWriter.thisInstance(valueEncoder.encode(self));
            } else {
                thisRecord = RecordWriter.thisInstanceRef(ObjectIdRegistry.idOf(self));
            }
        }

        byte[] argsRecord = null;
        if (allArguments != null) {
            argsRecord = RecordWriter.arguments(valueEncoder.encode(allArguments));
        }

        return concatOptional(startRecord, thisRecord, argsRecord);
    }

    private static Map<String, Object> buildExceptionData(Throwable throwable) {
        List<String> stacktrace = Stream.of(throwable.getStackTrace())
                .map(StackTraceElement::toString)
                .toList();
        return Map.of(
                "message", String.valueOf(throwable.getMessage()),
                "stacktrace", stacktrace
        );
    }

    private static byte[] concatOptional(byte[]... parts) {
        int totalLen = 0;
        for (byte[] part : parts) {
            if (part != null) totalLen += part.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] part : parts) {
            if (part != null) {
                System.arraycopy(part, 0, result, pos, part.length);
                pos += part.length;
            }
        }
        return result;
    }
}
