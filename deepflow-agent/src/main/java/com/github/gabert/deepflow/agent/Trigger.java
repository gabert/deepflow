package com.github.gabert.deepflow.agent;

import java.util.concurrent.atomic.AtomicBoolean;

public class Trigger {
    public static final AtomicBoolean EXECUTION_MODE = new AtomicBoolean(false);

    public static boolean shouldExecuteOnEnter(String triggerFunction, String methodToEvaluate) {
        if (triggerFunction == null || triggerFunction.isBlank()) {
            return Boolean.TRUE;
        }

        if (isTriggerMethodReached(triggerFunction, methodToEvaluate)) {
            Trigger.EXECUTION_MODE.set(Boolean.TRUE);
        }

        return Trigger.EXECUTION_MODE.get();
    }

    public static boolean shouldExecuteOnExit(String triggerFunction, String methodToEvaluate) {
        if (triggerFunction == null && triggerFunction.isBlank()) {
            return Boolean.TRUE;
        }

        if (Trigger.EXECUTION_MODE.get()) {
            if (isTriggerMethodReached(triggerFunction, methodToEvaluate)) {
                Trigger.EXECUTION_MODE.set(Boolean.FALSE);
            }

            return Boolean.TRUE;
        } else {
            return Boolean.FALSE;
        }
    }

    // FIXME: Must be thread scoped
    private static boolean isTriggerMethodReached(String triggerFunction, String methodToEvaluate) {
        return methodToEvaluate.equals(triggerFunction);
    }
}
