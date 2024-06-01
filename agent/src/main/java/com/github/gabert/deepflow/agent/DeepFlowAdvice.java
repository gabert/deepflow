package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.serializer.Destination;
import com.github.gabert.deepflow.serializer.FileDestination;
import com.github.gabert.deepflow.serializer.MetaIdTypeAdapterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
    public final static Map<String, Integer> DEPTH = new HashMap<>();
    public final static Gson GSON_DATA;
    public static final Gson GSON_EXCEPTION;
    public final static String DELIMITER = ";";
    public static Destination DESTINATION;

    static {
        GSON_EXCEPTION = new Gson();
        GSON_DATA = new GsonBuilder()
                .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
                .create();
    }

    public static void setup(AgentConfig agentConfig) {
        CONFIG = agentConfig;
        DESTINATION = new FileDestination(agentConfig, generateSessionId());
    }

    public static String generateSessionId() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return now.format(formatter);
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.AllArguments Object[] allArguments) {

//        if ( ! Trigger.shouldExecuteOnEnter(CONFIG.getTriggerOn(), method) ) {
//            return;
//        }

        LocalTime ts = LocalTime.now();

        String data = Stream.of(allArguments).
                map(GSON_DATA::toJson).
                collect(Collectors.joining(", "));

        String methodSignature = transformMethodSignature(method);

        sentToDestination("MS" + DELIMITER + methodSignature);
        sentToDestination("TS" + DELIMITER + ts);
        sentToDestination("AR" + DELIMITER +  "[" + data + "]");

        incrementCounter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable) {

//        if ( ! Trigger.shouldExecuteOnExit(CONFIG.getTriggerOn(), method) ) {
//            return;
//        }

        decrementCounter();

        if (throwable != null) {
            String exceptionString = GSON_EXCEPTION.toJson(new ExceptionInfo(throwable));
            sentToDestination("EX" + DELIMITER + exceptionString);
        } else {
            sentToDestination("RE" + DELIMITER + GSON_DATA.toJson(returned));
        }

        LocalTime ts = LocalTime.now();

        String methodSignature = transformMethodSignature(method);

        sentToDestination("TE" + DELIMITER + ts);
        sentToDestination("ME" + DELIMITER + methodSignature);
    }

    public static String transformMethodSignature(Method method) {
        String methodName = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();
        Class<?> returnType = method.getReturnType();
        String modifiers = Modifier.toString(method.getModifiers());

        String argumentTypes = Arrays.stream(method.getParameterTypes())
                .map(DeepFlowAdvice::formatClassName)
                .collect(Collectors.joining(", "));

        return String.format("%s.%s(%s) -> %s [%s]",
                formatClassName(declaringClass),
                methodName,
                argumentTypes,
                formatClassName(returnType),
                modifiers);
    }

    public static String formatClassName(Class<?> clazz) {
        String fullClassName = clazz.getName();
        int lastDotIndex = fullClassName.lastIndexOf('.');

        // Replace the last occurrence of '.' with '::'
        if (lastDotIndex != -1) {
            fullClassName = fullClassName.substring(0, lastDotIndex) + "::" + fullClassName.substring(lastDotIndex + 1);
        }

        return fullClassName;

//        return clazz.getPackageName() + "::" + clazz.getSimpleName();
    }

    public static void incrementCounter() {
        String threadName = Thread.currentThread().getName();
        DEPTH.compute(threadName, (k, v) -> (v == null) ? 0 : v + 1);
    }

    public static void decrementCounter() {
        String threadName = Thread.currentThread().getName();
        DEPTH.compute(threadName, (k, v) -> (v == null) ? 0 : v - 1);
    }

    public static void sentToDestination(String data) {
        String threadName = Thread.currentThread().getName();
        DEPTH.compute(threadName, (k, v) -> (v == null) ? 0 : v);

        String line = DEPTH.get(threadName) + DELIMITER + threadName + DELIMITER + data + System.lineSeparator();

        DESTINATION.send(line, threadName);
    }

    public static class ExceptionInfo {
        private final String message;
        private final List<String> stacktrace;

        public ExceptionInfo(Throwable exception) {
            this.message = exception.getMessage();
            this.stacktrace = getStackTraceAsString(exception);
        }

        private List<String> getStackTraceAsString(Throwable exception) {
            return Stream.of(exception.getStackTrace())
                    .map(StackTraceElement::toString)
                    .toList();
        }
    }
}