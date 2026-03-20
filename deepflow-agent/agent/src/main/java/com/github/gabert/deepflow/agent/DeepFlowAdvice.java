package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.recorder.RecordBuffer;
import com.github.gabert.deepflow.recorder.RecordWriter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

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
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void setup(AgentConfig config) {
        CONFIG = config;
        EXPAND_THIS = config.isExpandThis();
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

            byte[] argsCbor = Codec.encode(allArguments);
            byte[] record;
            if (self != null && EXPAND_THIS) {
                byte[] thisInstanceCbor = Codec.encode(self);
                record = RecordWriter.logEntry(signature, threadName, timestamp, callerLine, depth, thisInstanceCbor, argsCbor);
            } else if (self != null) {
                long thisId = ObjectIdRegistry.idOf(self);
                record = RecordWriter.logEntryWithThisRef(signature, threadName, timestamp, callerLine, depth, thisId, argsCbor);
            } else {
                record = RecordWriter.logEntry(signature, threadName, timestamp, callerLine, depth, null, argsCbor);
            }
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

            if (throwable != null) {
                byte[] excCbor = Codec.encode(buildExceptionData(throwable));
                byte[] record = RecordWriter.logExitException(threadName, timestamp, excCbor);
                RECORD_BUFFER.offer(record);
            } else {
                boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                byte[] returnCbor = isVoid ? null : Codec.encode(returned);
                byte[] record = RecordWriter.logExit(threadName, timestamp, returnCbor, isVoid);
                RECORD_BUFFER.offer(record);
            }
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