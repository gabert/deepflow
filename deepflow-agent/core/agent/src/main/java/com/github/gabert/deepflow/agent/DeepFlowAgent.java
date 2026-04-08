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
    private static final String AGENT_PACKAGE = "com.github.gabert.deepflow.agent";
    private static final String AGENT_RECORDER_EXCLUDE_PACKAGE = "com.github.gabert.deepflow.recorder";
    private static final String AGENT_CODEC_EXCLUDE_PACKAGE = "com.github.gabert.deepflow.codec";
    private static final String AGENT_SHADED_EXCLUDE_PACKAGE = "com.github.gabert.deepflow.shaded";

    public static void premain(String agentArgs,
                               Instrumentation instrumentation) {

        AgentConfig agentConfig;

        try {
            agentConfig = AgentConfig.getInstance(agentArgs);
        } catch (Exception e) {
            System.err.println("[DeepFlow] Failed to load agent config. Agent disabled.");
            e.printStackTrace();
            return;
        }

        System.out.println("[DeepFlow] Agent attached — destination=" + agentConfig.getDestination()
                + ", matchers=" + agentConfig.getMatchersInclude().size() + " include / "
                + agentConfig.getMatchersExclude().size() + " exclude");

        DeepFlowAdvice.setup(agentConfig);

        Advice advice = Advice.to(DeepFlowAdvice.class);

        ElementMatcher.Junction<TypeDescription> matcherInclude = ElementMatchers.none();
        for (String regex : agentConfig.getMatchersInclude()) {
            matcherInclude = matcherInclude.or(ElementMatchers.nameMatches(regex));
        }

        ElementMatcher.Junction<TypeDescription> typeMatcher = matcherInclude;
        if (!agentConfig.getMatchersExclude().isEmpty()) {
            ElementMatcher.Junction<TypeDescription> matcherExclude = ElementMatchers.none();
            for (String regex : agentConfig.getMatchersExclude()) {
                matcherExclude = matcherExclude.or(ElementMatchers.nameMatches(regex));
            }
            typeMatcher = typeMatcher.and(ElementMatchers.not(matcherExclude));
        }

        ElementMatcher.Junction<TypeDescription> matcherAgentPackage =
                ElementMatchers.nameStartsWith(AGENT_PACKAGE)
                        .or(ElementMatchers.nameStartsWith(AGENT_RECORDER_EXCLUDE_PACKAGE))
                        .or(ElementMatchers.nameStartsWith(AGENT_CODEC_EXCLUDE_PACKAGE))
                        .or(ElementMatchers.nameStartsWith(AGENT_SHADED_EXCLUDE_PACKAGE))
                        .or(ElementMatchers.nameContains("$$"));

        new AgentBuilder.Default()
                .type(typeMatcher)
                .transform((DynamicType.Builder<?> builder,
                            TypeDescription type,
                            ClassLoader loader,
                            JavaModule module,
                            ProtectionDomain pd) -> builder.visit(
                                    advice.on(ElementMatchers.isMethod()
                                            .and(ElementMatchers.not(ElementMatchers.isGetter()))
                                            .and(ElementMatchers.not(ElementMatchers.isSetter()))
                                            .and(ElementMatchers.not(ElementMatchers.named("toString")))
                                            .and(ElementMatchers.not(ElementMatchers.named("equals")))
                                            .and(ElementMatchers.not(ElementMatchers.named("hashCode"))))))
                .disableClassFormatChanges() // Prevent class format changes for frameworks
                .ignore(matcherAgentPackage)
                .installOn(instrumentation);
    }
}