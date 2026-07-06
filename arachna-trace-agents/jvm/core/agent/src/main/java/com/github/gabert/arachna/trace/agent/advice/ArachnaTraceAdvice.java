package com.github.gabert.arachna.trace.agent.advice;

import com.github.gabert.arachna.trace.agent.recording.RequestRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice that delegates entry/exit recording to the active
 * {@link RequestRecorder}. The {@code RECORDER} field is written once at
 * agent startup by {@code ArachnaTraceAgent.premain} and read by every traced
 * method invocation. Visibility relies on the happens-before from agent
 * startup completing before any instrumented class is loaded.
 *
 * <p>The method is identified by {@code @Advice.Origin Class} plus a
 * {@code "#m#d"} (name + descriptor) string — both constant-pool loads.
 * A {@code @Advice.Origin Method} would instead be re-resolved via
 * {@code Class.getDeclaredMethod} on every invocation, because with
 * {@code disableClassFormatChanges()} ByteBuddy cannot add the synthetic
 * caching field. Per-method metadata is cached behind the key in
 * {@code MethodMetaCache}.</p>
 *
 * <p>The {@link #onEnter} advice returns a boolean indicating whether the
 * entry was committed (UUID pushed onto {@code CALL_STACK} <em>and</em>
 * {@code MS} record queued). ByteBuddy passes this value as
 * {@link Advice.Enter} to {@link #onExit}, which only invokes
 * {@code recordExit} when entry succeeded. This guarantees push/pop
 * balance: a failed entry leaves both {@code DEPTH} and {@code CALL_STACK}
 * in their pre-entry state and suppresses the matching exit, so no later
 * call ever pairs against a wrong UUID.</p>
 *
 * <p>This is the full variant, used when exit-time argument capture
 * ({@code AX}) is enabled; {@link ArachnaTraceAdviceNoAx} and
 * {@link ArachnaTraceAdviceStructural} avoid boxing values the
 * configuration would discard.</p>
 */
public class ArachnaTraceAdvice {
    public static volatile RequestRecorder RECORDER;

    public static void setup(RequestRecorder recorder) {
        RECORDER = recorder;
    }

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Origin Class<?> declaringType,
                                  @Advice.Origin("#m#d") String methodKey,
                                  @Advice.This(optional = true) Object self,
                                  @Advice.AllArguments Object[] allArguments) {
        RequestRecorder recorder = RECORDER;
        if (recorder == null) return false;
        return recorder.recordEntry(declaringType, methodKey, self, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean entryRecorded,
                              @Advice.Origin Class<?> declaringType,
                              @Advice.Origin("#m#d") String methodKey,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {
        if (!entryRecorded) return;
        RequestRecorder recorder = RECORDER;
        if (recorder != null) {
            recorder.recordExit(declaringType, methodKey, returned, throwable, allArguments);
        }
    }
}
