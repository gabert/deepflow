package com.github.gabert.arachna.trace.agent.advice;

import com.github.gabert.arachna.trace.agent.bootstrap.PropagatingRunnable;
import com.github.gabert.arachna.trace.agent.bootstrap.RequestContext;
import net.bytebuddy.asm.Advice;

import java.util.UUID;

public class ExecutorAdvice {

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
