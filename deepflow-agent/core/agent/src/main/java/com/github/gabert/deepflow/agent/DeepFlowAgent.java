package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.advice.DeepFlowAdvice;
import com.github.gabert.deepflow.agent.advice.ExecutorAdvice;
import com.github.gabert.deepflow.agent.advice.ForkJoinAdvice;
import com.github.gabert.deepflow.agent.recording.RequestRecorder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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

        // Inject bootstrap classes BEFORE DeepFlowAdvice or any executor advice
        // classes are used, so that RequestContext is loaded from bootstrap (not
        // system CL) when first referenced.
        if (agentConfig.isPropagateRequestId()) {
            injectBootstrapClasses(instrumentation);
        }

        System.out.println("[DeepFlow] Agent attached — destination=" + agentConfig.getDestination()
                + ", matchers=" + agentConfig.getMatchersInclude().size() + " include / "
                + agentConfig.getMatchersExclude().size() + " exclude");

        RecorderManager manager = RecorderManager.create(agentConfig);
        if (manager != null) {
            DeepFlowAdvice.setup(new RequestRecorder(manager.getBuffer(), agentConfig));
        }

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

        if (agentConfig.isPropagateRequestId()) {
            installExecutorInstrumentation(instrumentation);
        }
    }

    private static void injectBootstrapClasses(Instrumentation instrumentation) {
        // Build a small temp JAR with just the classes needed by executor advice
        // when inlined into JDK classes (ThreadPoolExecutor, ForkJoinPool).
        // We read class bytes as resources to avoid triggering class loading —
        // if the system CL loaded them first, we'd get two copies and the
        // ThreadLocals would be different.
        String[] classResources = {
                "com/github/gabert/deepflow/agent/bootstrap/RequestContext.class",
                "com/github/gabert/deepflow/agent/bootstrap/PropagatingRunnable.class",
                "com/github/gabert/deepflow/agent/bootstrap/PropagatingCallable.class"
        };

        try {
            File tempJar = File.createTempFile("deepflow-bootstrap", ".jar");
            tempJar.deleteOnExit();

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {
                ClassLoader cl = DeepFlowAgent.class.getClassLoader();
                for (String resource : classResources) {
                    try (InputStream is = cl.getResourceAsStream(resource)) {
                        if (is == null) {
                            throw new RuntimeException("Cannot find resource: " + resource);
                        }
                        jos.putNextEntry(new JarEntry(resource));
                        is.transferTo(jos);
                        jos.closeEntry();
                    }
                }
            }

            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar));

            // After injection, add a module reads edge so java.base can access
            // our bootstrap-injected classes (which land in the unnamed module).
            Class<?> injectedClass = Class.forName(
                    "com.github.gabert.deepflow.agent.bootstrap.RequestContext", true, null);
            Module javaBase = Object.class.getModule();
            Module unnamedBootstrap = injectedClass.getModule();
            instrumentation.redefineModule(
                    javaBase,
                    Set.of(unnamedBootstrap),
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Map.of());
        } catch (Exception e) {
            System.err.println("[DeepFlow] Warning: failed to inject bootstrap classes. "
                    + "Cross-thread request ID propagation may not work.");
            e.printStackTrace();
        }
    }

    private static void installExecutorInstrumentation(Instrumentation instrumentation) {
        // ThreadPoolExecutor.execute(Runnable) — covers @Async, ExecutorService.submit(),
        // ScheduledThreadPoolExecutor (extends ThreadPoolExecutor)
        // Override default ignore rules — AgentBuilder.Default ignores java.* classes,
        // but we specifically need to retransform ThreadPoolExecutor and ForkJoinPool.
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.is(ThreadPoolExecutor.class))
                .transform((builder, type, loader, module, pd) -> builder.visit(
                        Advice.to(ExecutorAdvice.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArgument(0, Runnable.class)))))
                .installOn(instrumentation);

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.is(ForkJoinPool.class))
                .transform((builder, type, loader, module, pd) -> builder.visit(
                        Advice.to(ForkJoinAdvice.ExecuteRunnable.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArgument(0, Runnable.class))))
                        .visit(Advice.to(ForkJoinAdvice.SubmitCallable.class)
                                .on(ElementMatchers.named("submit")
                                        .and(ElementMatchers.takesArgument(0, java.util.concurrent.Callable.class)))))
                .installOn(instrumentation);

        System.out.println("[DeepFlow] Executor instrumentation installed (request ID propagation)");
    }
}
