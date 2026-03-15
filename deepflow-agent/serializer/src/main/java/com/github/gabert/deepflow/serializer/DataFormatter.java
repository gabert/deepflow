package com.github.gabert.deepflow.serializer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;

public class DataFormatter {
    private static final String METHOD_FORMAT = "%s.%s(%s) -> %s [%s]";

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

        return String.format(METHOD_FORMAT,
                formatClassName(declaringClass),
                methodName,
                argumentTypes,
                formatClassName(returnType),
                modifiers);
    }
}
