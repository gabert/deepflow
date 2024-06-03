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

        ElementMatcher.Junction<TypeDescription> matcherInclude = ElementMatchers.none();
        for (String regex : agentConfig.getMatchersInclude()) {
            matcherInclude = matcherInclude.or(ElementMatchers.nameMatches(regex));
        }

        ElementMatcher.Junction<TypeDescription> matcherExclude = ElementMatchers.none();
        for (String regex : agentConfig.getMatchersExclude()) {
            matcherExclude = matcherExclude.or(ElementMatchers.nameMatches(regex));
        }

        ElementMatcher.Junction<TypeDescription> matcherAgentPackage = ElementMatchers.nameStartsWith("com.github.gabert.deepflow.serializer");

        new AgentBuilder.Default()
//                .type(matcherInclude.and(ElementMatchers.not(matcherExclude)))
                .type(matcherInclude.and(ElementMatchers.not(matcherExclude)).and(ElementMatchers.not(matcherAgentPackage)))
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