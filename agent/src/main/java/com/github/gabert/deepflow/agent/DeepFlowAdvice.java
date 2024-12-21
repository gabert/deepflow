package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.serializer.Destination;
import com.github.gabert.deepflow.serializer.MetaIdTypeAdapterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
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
        DESTINATION = agentConfig.getDestination();
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.AllArguments Object[] allArguments) {

        LocalTime ts = LocalTime.now();

        String argsData = Stream.of(allArguments).
                map(GSON_DATA::toJson).
                collect(Collectors.joining(", "));

        String methodSignature = transformMethodSignature(method);
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        int callerLine = stackTraceElements.length >= 3 ? stackTraceElements[2].getLineNumber() : 0;

        sendToDestination(formatLine("MS", methodSignature));
        sendToDestination(formatLine("TS", ts));
        sendToDestination(formatLine("CL", callerLine));
        sendToDestination(formatLine("AR", "[" + argsData + "]"));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {

        if (Void.TYPE.equals(method.getGenericReturnType())) {
            sendToDestination(formatLine("RT", "VOID"));
        } else if (throwable != null) {
            sendToDestination(formatLine("RT" , "EXCEPTION"));
        } else {
            sendToDestination(formatLine("RT", "VALUE"));
        }

        if (throwable != null) {
            String exceptionString = GSON_EXCEPTION.toJson(new ExceptionInfo(throwable));
            sendToDestination(formatLine("RE", exceptionString));
        } else {
            sendToDestination(formatLine("RE", GSON_DATA.toJson(returned)));
        }

        LocalTime te = LocalTime.now();

        String methodSignature = transformMethodSignature(method);

        sendToDestination(formatLine("TE", te));
        sendToDestination(formatLine("ME", methodSignature));
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

        if (lastDotIndex != -1) {
            fullClassName = fullClassName.substring(0, lastDotIndex) + "::" + fullClassName.substring(lastDotIndex + 1);
        }

        return fullClassName;
    }

    public static String formatLine(String tag, Object data) {
        return tag + DELIMITER + data.toString() + "\n";
    }

    public static void sendToDestination(String data) {
        String threadName = Thread.currentThread().getName();

        try {
            DESTINATION.send(data, threadName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
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