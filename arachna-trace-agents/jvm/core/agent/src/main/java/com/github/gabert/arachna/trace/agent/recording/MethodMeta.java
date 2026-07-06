package com.github.gabert.arachna.trace.agent.recording;

/**
 * Immutable per-method metadata resolved once per traced method and cached by
 * {@link MethodMetaCache}. Holds everything the hot path needs so that no
 * reflection, formatting, or parameter-name resolution happens per call.
 *
 * <p>Memory discipline: the agent runs for the JVM's lifetime and the
 * instrumented surface can be huge, so this holds exactly one representation
 * of each fact — the signature only as the UTF-8 bytes the wire needs (the
 * formatted String is not retained), and the parameter-key array shared with
 * {@link ParameterNamesResolver}'s cache where names were resolved.</p>
 */
public final class MethodMeta {
    private final byte[] signatureUtf8;
    private final Object[] paramKeys;
    private final boolean voidReturn;

    MethodMeta(byte[] signatureUtf8, Object[] paramKeys, boolean voidReturn) {
        this.signatureUtf8 = signatureUtf8;
        this.paramKeys = paramKeys;
        this.voidReturn = voidReturn;
    }

    /**
     * UTF-8 bytes of the formatted trace-line signature (see
     * {@link MethodSignatureFormatter}). Callers must not mutate.
     */
    public byte[] signatureUtf8() {
        return signatureUtf8;
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
