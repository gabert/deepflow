package com.github.gabert.deepflow.agent.advice;

import com.github.gabert.deepflow.agent.recording.RequestRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice that delegates entry/exit recording to the active
 * {@link RequestRecorder}. The {@code RECORDER} field is written once at
 * agent startup by {@code DeepFlowAgent.premain} and read by every traced
 * method invocation. Visibility relies on the happens-before from agent
 * startup completing before any instrumented class is loaded.
 */
public class DeepFlowAdvice {
    public static volatile RequestRecorder RECORDER;

    public static void setup(RequestRecorder recorder) {
        RECORDER = recorder;
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.This(optional = true) Object self,
                               @Advice.AllArguments Object[] allArguments) {
        RequestRecorder recorder = RECORDER;
        if (recorder != null) {
            recorder.recordEntry(method, self, allArguments);
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {
        RequestRecorder recorder = RECORDER;
        if (recorder != null) {
            recorder.recordExit(method, returned, throwable, allArguments);
        }
    }
}
