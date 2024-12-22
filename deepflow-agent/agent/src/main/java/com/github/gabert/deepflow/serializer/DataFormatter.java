package com.github.gabert.deepflow.serializer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DataFormatter {
    public final static String DELIMITER = ";";

    public static String formatClassName(Class<?> clazz) {
        String fullClassName = clazz.getName();
        int lastDotIndex = fullClassName.lastIndexOf('.');

        if (lastDotIndex != -1) {
            fullClassName = fullClassName.substring(0, lastDotIndex) + "::" + fullClassName.substring(lastDotIndex + 1);
        }

        return fullClassName;
    }

    public static String transformMethodSignature(Method method) {
        String methodName = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();
        Class<?> returnType = method.getReturnType();
        String modifiers = Modifier.toString(method.getModifiers());

        String argumentTypes = Arrays.stream(method.getParameterTypes())
                .map(DataFormatter::formatClassName)
                .collect(Collectors.joining(", "));

        return String.format("%s.%s(%s) -> %s [%s]",
                formatClassName(declaringClass),
                methodName,
                argumentTypes,
                formatClassName(returnType),
                modifiers);
    }

    public static String formatLine(String tag, Object data) {
        return tag + DELIMITER + data.toString() + "\n";
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
