package com.github.gabert.arachna.trace.agent.recording;

import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches {@link MethodMeta} per traced method.
 *
 * <p>The hot path (inlined advice) identifies a method by its declaring
 * {@link Class} (a constant-pool class load) and a {@code name+descriptor}
 * key (a constant-pool string) — both are free at runtime, unlike a
 * {@code @Advice.Origin Method}, which ByteBuddy resolves via
 * {@code Class.getDeclaredMethod} on <em>every</em> invocation when the
 * class file format is frozen ({@code disableClassFormatChanges}).</p>
 *
 * <p>Storage is {@link ClassValue}-keyed so cached metadata is released when
 * the declaring class is unloaded (hot-reload containers cannot leak
 * metaspace), mirroring the discipline in {@link ParameterNamesResolver}.
 * The one-time build cost per method includes signature formatting and
 * parameter-name resolution — including the negative case, so classes
 * compiled without any name attributes resolve to positional keys exactly
 * once instead of re-parsing class bytes per call.</p>
 */
public final class MethodMetaCache {

    private static final ClassValue<ConcurrentHashMap<String, MethodMeta>> CACHE_BY_CLASS =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, MethodMeta> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private MethodMetaCache() {}

    /**
     * Returns the cached metadata for {@code methodKey} (= method name
     * immediately followed by its JVM descriptor, e.g.
     * {@code price(Lbench/Order;I)D}) on {@code declaringType}, building it
     * on first use.
     */
    public static MethodMeta get(Class<?> declaringType, String methodKey) {
        ConcurrentHashMap<String, MethodMeta> perClass = CACHE_BY_CLASS.get(declaringType);
        MethodMeta meta = perClass.get(methodKey);
        if (meta != null) return meta;
        meta = build(declaringType, methodKey);
        MethodMeta prev = perClass.putIfAbsent(methodKey, meta);
        return prev != null ? prev : meta;
    }

    /** The cache key for a reflective {@link Method} (test / non-advice callers). */
    public static String keyOf(Method method) {
        return method.getName() + Type.getMethodDescriptor(method);
    }

    // --- One-time metadata construction ---

    private static MethodMeta build(Class<?> declaringType, String methodKey) {
        int paren = methodKey.indexOf('(');
        String name = methodKey.substring(0, paren);
        String descriptor = methodKey.substring(paren);

        Method method = findMethod(declaringType, name, descriptor);
        if (method != null) {
            return new MethodMeta(
                    MethodSignatureFormatter.format(method),
                    ParameterNamesResolver.resolve(method),
                    method.getReturnType() == void.class);
        }

        // Descriptor-only fallback — should not happen for advice-originated
        // keys, but the hot path must never fail on a missing reflection hit.
        return new MethodMeta(
                declaringType.getName() + "." + name + descriptor,
                positionalKeys(Type.getArgumentTypes(descriptor).length),
                Type.getReturnType(descriptor).getSort() == Type.VOID);
    }

    private static Method findMethod(Class<?> declaringType, String name, String descriptor) {
        try {
            for (Method m : declaringType.getDeclaredMethods()) {
                if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(descriptor)) {
                    return m;
                }
            }
        } catch (Throwable t) {
            // fall through to the descriptor-only fallback
        }
        return null;
    }

    private static Object[] positionalKeys(int count) {
        Object[] keys = new Object[count];
        for (int i = 0; i < count; i++) keys[i] = i;
        return keys;
    }
}
