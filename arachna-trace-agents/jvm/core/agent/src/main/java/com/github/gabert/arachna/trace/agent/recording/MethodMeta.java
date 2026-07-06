package com.github.gabert.arachna.trace.agent.recording;

/**
 * Immutable per-method metadata resolved once per traced method and cached by
 * {@link MethodMetaCache}. Holds everything the hot path needs so that no
 * reflection, formatting, or parameter-name resolution happens per call.
 */
public final class MethodMeta {
    private final String signature;
    private final Object[] paramKeys;
    private final boolean voidReturn;

    MethodMeta(String signature, Object[] paramKeys, boolean voidReturn) {
        this.signature = signature;
        this.paramKeys = paramKeys;
        this.voidReturn = voidReturn;
    }

    /** Formatted trace-line signature (see {@link MethodSignatureFormatter}). */
    public String signature() {
        return signature;
    }

    /** AR/AX map keys — Strings (real names) or Integers (positional). */
    public Object[] paramKeys() {
        return paramKeys;
    }

    /** Whether the method's declared return type is {@code void}. */
    public boolean isVoidReturn() {
        return voidReturn;
    }
}
