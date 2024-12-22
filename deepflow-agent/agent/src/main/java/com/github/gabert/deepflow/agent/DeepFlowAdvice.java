package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.serializer.MethodLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
    public static MethodLogger METHOD_LOGGER;

    public static void setup(AgentConfig agentConfig) {
        CONFIG = agentConfig;
        METHOD_LOGGER = new MethodLogger(agentConfig.getDestination());
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.AllArguments Object[] allArguments) {

        METHOD_LOGGER.logEntry(method, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {

        METHOD_LOGGER.logExit(method, returned, throwable, allArguments);
    }
}