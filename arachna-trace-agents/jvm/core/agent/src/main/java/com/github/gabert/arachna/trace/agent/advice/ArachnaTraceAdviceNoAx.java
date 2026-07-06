package com.github.gabert.arachna.trace.agent.advice;

import com.github.gabert.arachna.trace.agent.recording.RequestRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Variant of {@link ArachnaTraceAdvice} installed when exit-time argument
 * capture ({@code AX}) is disabled — the default tag set. The exit advice
 * takes no {@code @AllArguments}, so ByteBuddy does not build a boxed
 * {@code Object[]} of the arguments a second time on every method exit
 * only for the recorder to discard it.
 *
 * <p>Selected once at premain by {@code ArachnaTraceAgent}; shares
 * {@link ArachnaTraceAdvice#RECORDER} and the entry/exit contract
 * documented there.</p>
 */
public class ArachnaTraceAdviceNoAx {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Origin Class<?> declaringType,
                                  @Advice.Origin("#m#d") String methodKey,
                                  @Advice.This(optional = true) Object self,
                                  @Advice.AllArguments Object[] allArguments) {
        RequestRecorder recorder = ArachnaTraceAdvice.RECORDER;
        if (recorder == null) return false;
        return recorder.recordEntry(declaringType, methodKey, self, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean entryRecorded,
                              @Advice.Origin Class<?> declaringType,
                              @Advice.Origin("#m#d") String methodKey,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable) {
        if (!entryRecorded) return;
        RequestRecorder recorder = ArachnaTraceAdvice.RECORDER;
        if (recorder != null) {
            recorder.recordExit(declaringType, methodKey, returned, throwable, null);
        }
    }
}
