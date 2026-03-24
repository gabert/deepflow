package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;
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
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
    public static RecordBuffer RECORD_BUFFER;
    private static boolean EXPAND_THIS;
    private static boolean SERIALIZE_VALUES;
    private static volatile SessionIdResolver SESSION_ID_RESOLVER;
    private static volatile boolean JPA_PROXY_RESOLVER_INITIALIZED;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void setup(AgentConfig config) {
        CONFIG = config;
        EXPAND_THIS = config.isExpandThis();
        SERIALIZE_VALUES = config.isSerializeValues();
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
                              @Advice.Thrown Throwable throwable) {

        recordExit(method, returned, throwable);
    }

    // --- Record entry ---

    public static void recordEntry(Method method, Object self, Object[] allArguments) {
        if (RECORD_BUFFER == null) return;
        initJpaProxyResolver();
        try {
            String signature = formatMethodSignature(method);
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();
            int depth = CALL_DEPTH.get();
            CALL_DEPTH.set(depth + 1);
            int callerLine = STACK_WALKER
                    .walk(s -> s.skip(1).findFirst())
                    .map(StackWalker.StackFrame::getLineNumber)
                    .orElse(0);

            String sessionId = getResolver().resolve();

            byte[] record = SERIALIZE_VALUES
                    ? buildSerializedEntry(sessionId, signature, threadName, timestamp, callerLine, depth, self, allArguments)
                    : RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine, depth);

            RECORD_BUFFER.offer(record);
        } catch (Throwable t) {
            System.err.println("Error recording entry.");
            t.printStackTrace();
        }
    }

    // --- Record exit ---

    public static void recordExit(Method method, Object returned, Throwable throwable) {
        if (RECORD_BUFFER == null) return;
        try {
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();
            CALL_DEPTH.set(Math.max(0, CALL_DEPTH.get() - 1));

            String sessionId = getResolver().resolve();

            byte[] record;
            if (SERIALIZE_VALUES) {
                if (throwable != null) {
                    byte[] excCbor = Codec.encode(buildExceptionData(throwable));
                    record = RecordWriter.logExitException(sessionId, threadName, timestamp, excCbor);
                } else {
                    boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                    byte[] returnCbor = isVoid ? null : Codec.encode(returned);
                    record = RecordWriter.logExit(sessionId, threadName, timestamp, returnCbor, isVoid);
                }
            } else {
                record = RecordWriter.logExitSimple(sessionId, threadName, timestamp);
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

    // --- Private: entry record building ---

    private static byte[] buildSerializedEntry(String sessionId, String signature, String threadName,
                                                long timestamp, int callerLine, int depth,
                                                Object self, Object[] allArguments) throws IOException {
        byte[] argsCbor = Codec.encode(allArguments);
        if (self == null) {
            return RecordWriter.logEntry(sessionId, signature, threadName, timestamp, callerLine, depth, null, argsCbor);
        }
        if (EXPAND_THIS) {
            byte[] thisInstanceCbor = Codec.encode(self);
            return RecordWriter.logEntry(sessionId, signature, threadName, timestamp, callerLine, depth, thisInstanceCbor, argsCbor);
        }
        long thisId = ObjectIdRegistry.idOf(self);
        return RecordWriter.logEntryWithThisRef(sessionId, signature, threadName, timestamp, callerLine, depth, thisId, argsCbor);
    }

    // --- SPI loading (lazy, deferred until first use) ---

    private static SessionIdResolver getResolver() {
        SessionIdResolver r = SESSION_ID_RESOLVER;
        if (r != null) return r;
        synchronized (DeepFlowAdvice.class) {
            r = SESSION_ID_RESOLVER;
            if (r != null) return r;
            r = loadSessionIdResolver(CONFIG, resolveClassLoader());
            SESSION_ID_RESOLVER = r;
            return r;
        }
    }

    private static final SessionIdResolver NOOP_RESOLVER = new SessionIdResolver() {
        @Override public String name() { return "noop"; }
        @Override public String resolve() { return null; }
    };

    private static void initJpaProxyResolver() {
        if (JPA_PROXY_RESOLVER_INITIALIZED) return;
        synchronized (DeepFlowAdvice.class) {
            if (JPA_PROXY_RESOLVER_INITIALIZED) return;
            JpaProxyResolver resolver = loadJpaProxyResolver(CONFIG, resolveClassLoader());
            if (resolver != null) {
                Codec.setJpaProxyResolver(resolver);
            }
            JPA_PROXY_RESOLVER_INITIALIZED = true;
        }
    }

    static SessionIdResolver loadSessionIdResolver(AgentConfig config, ClassLoader classLoader) {
        String name = config.getSessionResolver();
        if (name == null) {
            System.err.println("[DeepFlow] SessionIdResolver: no session_resolver configured, using built-in noop");
            return NOOP_RESOLVER;
        }
        SessionIdResolver found = loadSpiByName(SessionIdResolver.class, SessionIdResolver::name,
                name, "SessionIdResolver", classLoader);
        if (found != null) return found;
        System.err.println("[DeepFlow] WARNING: session_resolver='" + name
                + "' not found on classpath, session tracking disabled");
        return NOOP_RESOLVER;
    }

    static JpaProxyResolver loadJpaProxyResolver(AgentConfig config, ClassLoader classLoader) {
        String name = config.getJpaProxyResolver();
        if (name == null) {
            System.err.println("[DeepFlow] JpaProxyResolver: no jpa_proxy_resolver configured, proxy unwrapping disabled");
            return null;
        }
        JpaProxyResolver found = loadSpiByName(JpaProxyResolver.class, JpaProxyResolver::name,
                name, "JpaProxyResolver", classLoader);
        if (found != null) return found;
        System.err.println("[DeepFlow] WARNING: jpa_proxy_resolver='" + name
                + "' not found on classpath, JPA proxy resolution disabled");
        return null;
    }

    private static <T> T loadSpiByName(Class<T> spiType, Function<T, String> nameGetter,
                                        String name, String label, ClassLoader classLoader) {
        ServiceLoader<T> loader = ServiceLoader.load(spiType, classLoader);
        T selected = null;
        System.err.println("[DeepFlow] " + label + ": looking for '" + name + "'");
        for (T candidate : loader) {
            String candidateName = nameGetter.apply(candidate);
            System.err.println("[DeepFlow] " + label + ": found '" + candidateName
                    + "' (" + candidate.getClass().getName() + ")");
            if (name.equals(candidateName)) {
                selected = candidate;
            }
        }
        if (selected != null) {
            System.err.println("[DeepFlow] " + label + ": activated '" + nameGetter.apply(selected) + "'");
        }
        return selected;
    }

    private static ClassLoader resolveClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
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
