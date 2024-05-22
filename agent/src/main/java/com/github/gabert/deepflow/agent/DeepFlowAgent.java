package com.github.gabert.deepflow.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class DeepFlowAgent {
    public static void premain(String agentArgs,
                               Instrumentation instrumentation) {

        AgentConfig agentConfig;

        try {
            agentConfig = AgentConfig.getInstance(agentArgs);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        DeepFlowAdvice.setup(agentConfig);

        Advice advice = Advice.to(DeepFlowAdvice.class);

        ElementMatcher.Junction<TypeDescription> packageMatcher = ElementMatchers.none();
        for (String pkg : agentConfig.getPackageMatchers()) {
            packageMatcher = packageMatcher.or(ElementMatchers.nameStartsWith(pkg));
        }

        new AgentBuilder.Default()
                .type(packageMatcher)
                .transform((DynamicType.Builder<?> builder,
                            TypeDescription type,
                            ClassLoader loader,
                            JavaModule module,
                            ProtectionDomain pd) -> builder.visit(
                                    advice.on(ElementMatchers.isMethod()
                                            .and(ElementMatchers.not(ElementMatchers.named("toString")))
                                            .and(ElementMatchers.not(ElementMatchers.named("equals")))
                                            .and(ElementMatchers.not(ElementMatchers.named("hashCode"))))))
                .installOn(instrumentation);
    }
}