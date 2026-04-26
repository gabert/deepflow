package com.github.gabert.deepflow.agent;

import net.bytebuddy.asm.Advice;

public class ForkJoinAdvice {

    public static class ExecuteRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {

            long requestId = RequestContext.CURRENT_REQUEST_ID.get()[0];
            if (requestId != 0L) {
                runnable = new PropagatingRunnable(runnable, requestId);
            }
        }
    }

    public static class SubmitCallable {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 0, readOnly = false) java.util.concurrent.Callable<?> callable) {

            long requestId = RequestContext.CURRENT_REQUEST_ID.get()[0];
            if (requestId != 0L) {
                callable = new PropagatingCallable<>(callable, requestId);
            }
        }
    }
}
