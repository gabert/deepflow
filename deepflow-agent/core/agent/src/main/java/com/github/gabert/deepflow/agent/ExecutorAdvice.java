package com.github.gabert.deepflow.agent;

import net.bytebuddy.asm.Advice;

public class ExecutorAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {

        long requestId = RequestContext.CURRENT_REQUEST_ID.get()[0];
        if (requestId != 0L) {
            runnable = new PropagatingRunnable(runnable, requestId);
        }
    }
}
