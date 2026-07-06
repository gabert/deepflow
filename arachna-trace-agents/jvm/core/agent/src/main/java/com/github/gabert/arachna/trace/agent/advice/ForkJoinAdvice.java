package com.github.gabert.arachna.trace.agent.advice;

import com.github.gabert.arachna.trace.agent.bootstrap.PropagatingCallable;
import com.github.gabert.arachna.trace.agent.bootstrap.PropagatingRunnable;
import com.github.gabert.arachna.trace.agent.bootstrap.RequestContext;
import net.bytebuddy.asm.Advice;

import java.util.UUID;

public class ForkJoinAdvice {

    public static class ExecuteRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {

            long requestId = RequestContext.currentRequestId();
            if (requestId != 0L) {
                UUID parentCallId = RequestContext.peekParentCallId();
                runnable = new PropagatingRunnable(runnable, requestId, parentCallId);
            }
        }
    }

    public static class SubmitCallable {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 0, readOnly = false) java.util.concurrent.Callable<?> callable) {

            long requestId = RequestContext.currentRequestId();
            if (requestId != 0L) {
                UUID parentCallId = RequestContext.peekParentCallId();
                callable = new PropagatingCallable<>(callable, requestId, parentCallId);
            }
        }
    }
}
