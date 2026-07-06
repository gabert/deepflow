package com.github.gabert.arachna.trace.agent.advice;

import com.github.gabert.arachna.trace.agent.recording.RequestRecorder;
import net.bytebuddy.asm.Advice;

/**
 * Variant of {@link ArachnaTraceAdvice} installed when
 * {@code serialize_values=false}. Only structural records are emitted in
 * that mode, so neither {@code this}, the arguments, nor the return value
 * are ever read — this advice binds none of them, eliminating all boxing
 * and array allocation from both entry and exit.
 *
 * <p>{@code onThrowable} is still set so the exit advice runs (and the
 * {@code ME} record is emitted) on exceptional exits.</p>
 *
 * <p>Selected once at premain by {@code ArachnaTraceAgent}; shares
 * {@link ArachnaTraceAdvice#RECORDER} and the entry/exit contract
 * documented there.</p>
 */
public class ArachnaTraceAdviceStructural {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Origin Class<?> declaringType,
                                  @Advice.Origin("#m#d") String methodKey) {
        RequestRecorder recorder = ArachnaTraceAdvice.RECORDER;
        if (recorder == null) return false;
        return recorder.recordEntry(declaringType, methodKey, null, null);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean entryRecorded,
                              @Advice.Origin Class<?> declaringType,
                              @Advice.Origin("#m#d") String methodKey) {
        if (!entryRecorded) return;
        RequestRecorder recorder = ArachnaTraceAdvice.RECORDER;
        if (recorder != null) {
            recorder.recordExit(declaringType, methodKey, null, null, null);
        }
    }
}
