package com.github.gabert.deepflow.agent.recording;

import com.github.gabert.deepflow.agent.AgentConfig;
import com.github.gabert.deepflow.agent.bootstrap.RequestContext;
import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import com.github.gabert.deepflow.agent.spi.SpiLoader;
import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.record.RecordWriter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private static final String METHOD_FORMAT = "%s.%s(%s) -> %s [%s]";

    private final AgentConfig config;
    private final RecordBuffer recordBuffer;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final boolean emitTi;
    private final boolean emitAr;
    private final boolean emitRt;
    private final boolean emitAx;
    private final int maxValueSize;

    private volatile SessionIdResolver sessionIdResolver;
    private volatile boolean jpaProxyResolverInitialized;

    public RequestRecorder(RecordBuffer recordBuffer, AgentConfig config) {
        this.recordBuffer = recordBuffer;
        this.config = config;
        this.expandThis = config.isExpandThis();
        this.serializeValues = config.isSerializeValues();
        this.emitTi = config.shouldEmit("TI");
        this.emitAr = config.shouldEmit("AR");
        this.emitRt = config.shouldEmit("RT") || config.shouldEmit("RE");
        this.emitAx = config.shouldEmit("AX");
        this.maxValueSize = config.getMaxValueSize();
    }

    public RecordBuffer getRecordBuffer() {
        return recordBuffer;
    }

    // --- Record entry ---

    public void recordEntry(Method method, Object self, Object[] allArguments) {
        if (recordBuffer == null) return;
        initJpaProxyResolver();
        try {
            String signature = formatMethodSignature(method);
            String threadName = Thread.currentThread().getName();
            long timestamp = System.nanoTime();
            int callerLine = STACK_WALKER
                    .walk(s -> s.skip(1).findFirst())
                    .map(StackWalker.StackFrame::getLineNumber)
                    .orElse(0);

            String sessionId = getResolver().resolve();

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

            String sessionId = getResolver().resolve();

            byte[] record;
            if (serializeValues) {
                byte[] exitArgsCbor = (emitAx && emitAr) ? encodeWithLimit(allArguments) : null;

                byte[] returnRecord;
                if (!emitRt) {
                    returnRecord = RecordWriter.returnVoid();
                } else if (throwable != null) {
                    returnRecord = RecordWriter.exception(encodeWithLimit(buildExceptionData(throwable)));
                } else {
                    boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                    returnRecord = isVoid
                            ? RecordWriter.returnVoid()
                            : RecordWriter.returnValue(encodeWithLimit(returned));
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
                thisRecord = RecordWriter.thisInstance(encodeWithLimit(self));
            } else {
                thisRecord = RecordWriter.thisInstanceRef(ObjectIdRegistry.idOf(self));
            }
        }

        byte[] argsRecord = null;
        if (allArguments != null) {
            argsRecord = RecordWriter.arguments(encodeWithLimit(allArguments));
        }

        return concatOptional(startRecord, thisRecord, argsRecord);
    }

    // --- Private: value encoding with truncation ---

    private byte[] encodeWithLimit(Object obj) throws IOException {
        byte[] encoded = Codec.encode(obj);
        if (maxValueSize > 0 && encoded.length > maxValueSize) {
            return Codec.encode(Map.of("__truncated", true, "original_size", encoded.length));
        }
        return encoded;
    }

    public static Map<String, Object> buildExceptionData(Throwable throwable) {
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

    // --- SPI loading (lazy, deferred until first use) ---

    private SessionIdResolver getResolver() {
        SessionIdResolver r = sessionIdResolver;
        if (r != null) return r;
        synchronized (this) {
            r = sessionIdResolver;
            if (r != null) return r;
            r = SpiLoader.loadSessionIdResolver(config, SpiLoader.resolveClassLoader());
            sessionIdResolver = r;
            return r;
        }
    }

    private void initJpaProxyResolver() {
        if (jpaProxyResolverInitialized) return;
        synchronized (this) {
            if (jpaProxyResolverInitialized) return;
            var resolver = SpiLoader.loadJpaProxyResolver(config, SpiLoader.resolveClassLoader());
            if (resolver != null) {
                Codec.setJpaProxyResolver(resolver);
            }
            jpaProxyResolverInitialized = true;
        }
    }

    // --- Method signature formatting ---

    private static String formatMethodSignature(Method method) {
        String argumentTypes = Arrays.stream(method.getParameterTypes())
                .map(RequestRecorder::formatClassName)
                .collect(Collectors.joining(", "));

        return String.format(METHOD_FORMAT,
                formatClassName(method.getDeclaringClass()),
                method.getName(),
                argumentTypes,
                formatClassName(method.getReturnType()),
                Modifier.toString(method.getModifiers()));
    }

    private static String formatClassName(Class<?> clazz) {
        if (clazz.isArray()) {
            return formatClassName(clazz.getComponentType()) + "[]";
        }
        String name = clazz.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1) {
            name = name.substring(0, lastDot) + "::" + name.substring(lastDot + 1);
        }
        return name;
    }
}
