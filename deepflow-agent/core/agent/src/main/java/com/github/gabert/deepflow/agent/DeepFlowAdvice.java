package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.recorder.buffer.RecordBuffer;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
    public static RecordBuffer RECORD_BUFFER;
    private static boolean EXPAND_THIS;
    private static boolean SERIALIZE_VALUES;
    private static boolean EMIT_TI;
    private static boolean EMIT_AR;
    private static boolean EMIT_RT;
    private static boolean EMIT_AX;
    private static int MAX_VALUE_SIZE;
    private static volatile SessionIdResolver SESSION_ID_RESOLVER;
    private static volatile boolean JPA_PROXY_RESOLVER_INITIALIZED;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    // Thread-local state lives in RequestContext (injected into bootstrap classloader)

    public static void setup(AgentConfig config) {
        CONFIG = config;
        EXPAND_THIS = config.isExpandThis();
        SERIALIZE_VALUES = config.isSerializeValues();
        EMIT_TI = config.shouldEmit("TI");
        EMIT_AR = config.shouldEmit("AR");
        EMIT_RT = config.shouldEmit("RT") || config.shouldEmit("RE");
        EMIT_AX = config.shouldEmit("AX");
        MAX_VALUE_SIZE = config.getMaxValueSize();
        RecorderManager manager = RecorderManager.create(config);
        RECORD_BUFFER = manager != null ? manager.getBuffer() : null;
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.This(optional = true) Object self,
                               @Advice.AllArguments Object[] allArguments) {

        recordEntry(method, self, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {

        recordExit(method, returned, throwable, allArguments);
    }

    // --- Record entry ---

    public static void recordEntry(Method method, Object self, Object[] allArguments) {
        if (RECORD_BUFFER == null) return;
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

            int[] depthHolder = RequestContext.DEPTH.get();
            long[] requestIdHolder = RequestContext.CURRENT_REQUEST_ID.get();
            if (depthHolder[0] == 0) {
                requestIdHolder[0] = RequestContext.REQUEST_COUNTER.incrementAndGet();
            }
            depthHolder[0]++;
            long requestId = requestIdHolder[0];

            byte[] record;
            if (SERIALIZE_VALUES) {
                Object selfForCapture = EMIT_TI ? self : null;
                Object[] argsForCapture = EMIT_AR ? allArguments : null;
                record = buildSerializedEntry(sessionId, signature, threadName, timestamp, callerLine,
                        requestId, selfForCapture, argsForCapture);
            } else {
                record = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine,
                        requestId);
            }

            RECORD_BUFFER.offer(record);
        } catch (Throwable t) {
            System.err.println("Error recording entry.");
            t.printStackTrace();
        }
    }

    // --- Record exit ---

    public static void recordExit(Method method, Object returned, Throwable throwable,
                                   Object[] allArguments) {
        if (RECORD_BUFFER == null) return;
        int[] depthHolder = RequestContext.DEPTH.get();
        long requestId = RequestContext.CURRENT_REQUEST_ID.get()[0];
        if (depthHolder[0] > 0) {
            depthHolder[0]--;
        }
        try {
            String threadName = Thread.currentThread().getName();
            long timestamp = System.nanoTime();

            String sessionId = getResolver().resolve();

            byte[] record;
            if (SERIALIZE_VALUES) {
                byte[] exitArgsCbor = (EMIT_AX && EMIT_AR) ? encodeWithLimit(allArguments) : null;

                byte[] returnRecord;
                if (!EMIT_RT) {
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
            RECORD_BUFFER.offer(record);
        } catch (Throwable t) {
            System.err.println("Error recording exit.");
            t.printStackTrace();
        }
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

    // --- Private: value encoding with truncation ---

    private static byte[] encodeWithLimit(Object obj) throws IOException {
        byte[] encoded = Codec.encode(obj);
        if (MAX_VALUE_SIZE > 0 && encoded.length > MAX_VALUE_SIZE) {
            return Codec.encode(Map.of("__truncated", true, "original_size", encoded.length));
        }
        return encoded;
    }

    // --- Private: entry record building ---

    private static byte[] buildSerializedEntry(String sessionId, String signature, String threadName,
                                                long timestamp, int callerLine,
                                                long requestId,
                                                Object self, Object[] allArguments) throws IOException {
        byte[] startRecord = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine,
                requestId);

        byte[] thisRecord = null;
        if (self != null) {
            if (EXPAND_THIS) {
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

    private static SessionIdResolver getResolver() {
        SessionIdResolver r = SESSION_ID_RESOLVER;
        if (r != null) return r;
        synchronized (DeepFlowAdvice.class) {
            r = SESSION_ID_RESOLVER;
            if (r != null) return r;
            r = SpiLoader.loadSessionIdResolver(CONFIG, SpiLoader.resolveClassLoader());
            SESSION_ID_RESOLVER = r;
            return r;
        }
    }

    private static void initJpaProxyResolver() {
        if (JPA_PROXY_RESOLVER_INITIALIZED) return;
        synchronized (DeepFlowAdvice.class) {
            if (JPA_PROXY_RESOLVER_INITIALIZED) return;
            var resolver = SpiLoader.loadJpaProxyResolver(CONFIG, SpiLoader.resolveClassLoader());
            if (resolver != null) {
                Codec.setJpaProxyResolver(resolver);
            }
            JPA_PROXY_RESOLVER_INITIALIZED = true;
        }
    }

    // --- Method signature formatting ---

    private static final String METHOD_FORMAT = "%s.%s(%s) -> %s [%s]";

    private static String formatMethodSignature(Method method) {
        String argumentTypes = Arrays.stream(method.getParameterTypes())
                .map(DeepFlowAdvice::formatClassName)
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
