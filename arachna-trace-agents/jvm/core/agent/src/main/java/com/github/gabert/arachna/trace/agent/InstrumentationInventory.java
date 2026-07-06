package com.github.gabert.arachna.trace.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Writes the instrumentation inventory: one line per method the agent
 * actually wove advice into, in the exact signature format the traces
 * carry ({@code MS} tag / {@code calls.signature} column).
 *
 * <p>Purpose — the liveness sweep: after exercising the application
 * (regression suite, staging traffic), diff this file against the
 * signatures actually observed
 * ({@code GET /api/analysis/observed-signatures?session_id=...}).
 * Instrumented-but-never-called methods are code that survived a change
 * (often an AI-generated refactor) without ever executing — candidates
 * for removal, with evidence.</p>
 *
 * <p>Enabled by the {@code instrumentation_inventory=&lt;path&gt;} agent
 * config; off by default (zero cost when off). Lines are appended as
 * classes load, so the file is complete once the exercised code paths
 * have all been touched by the classloader.</p>
 *
 * <p><b>Format parity.</b> {@link #format(MethodDescription)} must
 * produce byte-identical output to
 * {@link com.github.gabert.arachna.trace.agent.recording.MethodSignatureFormatter#format(java.lang.reflect.Method)}
 * for the same method — otherwise the diff against observed signatures
 * is meaningless. Guarded by {@code InstrumentationInventoryFormatTest}.</p>
 */
public final class InstrumentationInventory extends AgentBuilder.Listener.Adapter {

    private final ElementMatcher<? super MethodDescription> methodMatcher;
    private final BufferedWriter writer;

    private InstrumentationInventory(ElementMatcher<? super MethodDescription> methodMatcher,
                                     BufferedWriter writer) {
        this.methodMatcher = methodMatcher;
        this.writer = writer;
    }

    /**
     * Opens the inventory file (truncating any previous run's content).
     * Returns {@code null} — inventory disabled — when the file cannot
     * be created; the agent must keep working without it.
     */
    public static InstrumentationInventory create(String path,
                                                  ElementMatcher<? super MethodDescription> methodMatcher) {
        try {
            Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            return new InstrumentationInventory(methodMatcher, writer);
        } catch (IOException e) {
            System.err.println("[ArachnaTrace] instrumentation_inventory: cannot write '" + path
                    + "' (" + e.getMessage() + ") — inventory disabled");
            return null;
        }
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
        synchronized (this) {
            try {
                for (MethodDescription.InDefinedShape method : typeDescription.getDeclaredMethods()) {
                    if (methodMatcher.matches(method)) {
                        writer.write(format(method));
                        writer.newLine();
                    }
                }
                writer.flush();
            } catch (IOException e) {
                System.err.println("[ArachnaTrace] instrumentation_inventory write failed: " + e.getMessage());
            }
        }
    }

    // --- Signature formatting (mirror of MethodSignatureFormatter) ---

    /**
     * Formats a ByteBuddy {@link MethodDescription} identically to the
     * reflective formatter used on the recording path:
     * {@code pkg::ClassName.methodName(argType, ...) -> returnType [modifiers]}.
     */
    public static String format(MethodDescription method) {
        String argumentTypes = method.getParameters().asTypeList().asErasures().stream()
                .map(InstrumentationInventory::formatClassName)
                .collect(Collectors.joining(", "));

        return String.format("%s.%s(%s) -> %s [%s]",
                formatClassName(method.getDeclaringType().asErasure()),
                method.getName(),
                argumentTypes,
                formatClassName(method.getReturnType().asErasure()),
                Modifier.toString(method.getModifiers()));
    }

    private static String formatClassName(TypeDescription type) {
        if (type.isArray()) {
            return formatClassName(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1) {
            name = name.substring(0, lastDot) + "::" + name.substring(lastDot + 1);
        }
        return name;
    }
}
