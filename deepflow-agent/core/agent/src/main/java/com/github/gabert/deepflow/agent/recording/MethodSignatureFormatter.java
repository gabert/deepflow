package com.github.gabert.deepflow.agent.recording;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Stringifies a {@link Method} into the trace-line format consumed by the
 * Python formatter (see {@code deepflow-formater/}).
 *
 * <p>Format: {@code pkg::ClassName.methodName(argType, ...) -> returnType [modifiers]}.
 *
 * <p>The {@code ::} separator between package and class name is intentional —
 * the Python parser depends on it to disambiguate package boundaries from
 * inner classes. Do not change without updating
 * {@code deepflow-formater/deepflow/agent_dump_processor.py}.</p>
 */
public final class MethodSignatureFormatter {
    private static final String METHOD_FORMAT = "%s.%s(%s) -> %s [%s]";

    private MethodSignatureFormatter() {}

    public static String format(Method method) {
        String argumentTypes = Arrays.stream(method.getParameterTypes())
                .map(MethodSignatureFormatter::formatClassName)
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
