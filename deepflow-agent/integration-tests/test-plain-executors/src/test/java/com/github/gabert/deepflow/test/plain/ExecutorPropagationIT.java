package com.github.gabert.deepflow.test.plain;

import com.github.gabert.deepflow.test.common.AgentProcess;
import com.github.gabert.deepflow.test.common.TraceBlock;
import com.github.gabert.deepflow.test.common.TraceData;
import com.github.gabert.deepflow.test.common.TraceFileParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutorPropagationIT {
    private TraceData traces;

    @BeforeAll
    void runScenarios() throws Exception {
        Path dumpDir = Files.createTempDirectory("deepflow-test-plain");

        Path agentJar = AgentProcess.findAgentJar();
        Path configFile = AgentProcess.writeConfig(dumpDir,
                "com\\.github\\.gabert\\.deepflow\\.test\\.plain\\.scenario\\..*");

        // Classpath = compiled main classes of this module
        Path classesDir = Path.of(Main.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        int exitCode = AgentProcess.runAndWait(
                agentJar, configFile, classesDir,
                Main.class.getName(), 30);
        assertEquals(0, exitCode, "Subprocess should exit cleanly");

        Path sessionDir = TraceFileParser.findSessionDir(dumpDir);
        traces = TraceFileParser.parse(sessionDir);

        assertFalse(traces.blocks().isEmpty(), "Should have captured trace records");

        // Diagnostic: print all entry blocks
        System.out.println("=== TRACE DIAGNOSTIC ===");
        traces.entries().forEach(b ->
                System.out.println("  RI=" + b.requestId()
                        + " TN=" + b.threadName()
                        + " MS=" + b.method()));
        System.out.println("=== END DIAGNOSTIC ===");
    }

    // ==================== Scenario 1: ThreadPoolExecutor.execute ====================

    @Test
    void threadPoolExecute_allCallsShareRequestId() {
        long ri = singleRiFor("Scenarios.threadPoolExecute");

        List<String> methods = traces.methodsForRequestId(ri);
        assertTrue(methods.stream().anyMatch(m -> m.contains("Work.doWork")),
                "Work.doWork should appear within the same request ID");
    }

    @Test
    void threadPoolExecute_spansMultipleThreads() {
        long ri = singleRiFor("Scenarios.threadPoolExecute");
        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1,
                "Should span main + pool thread, got: " + threads);
    }

    // ==================== Scenario 2: ExecutorService.submit(Callable) ====================

    @Test
    void executorServiceSubmit_callableSharesRequestId() {
        long ri = singleRiFor("Scenarios.executorServiceSubmit");

        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1, "Callable should run on pool thread");
        assertTrue(traces.methodsForRequestId(ri).stream()
                .anyMatch(m -> m.contains("Work.compute")));
    }

    // ==================== Scenario 3: CompletableFuture.supplyAsync ====================

    @Test
    void completableFutureSupply_supplierSharesRequestId() {
        long ri = singleRiFor("Scenarios.completableFutureSupply");

        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1,
                "supplyAsync should run on ForkJoinPool thread, got: " + threads);
    }

    // ==================== Scenario 4: CompletableFuture chain ====================

    @Test
    void completableFutureChain_allStagesShareRequestId() {
        long ri = singleRiFor("Scenarios.completableFutureChain");

        List<String> methods = traces.methodsForRequestId(ri);
        long computeCount = methods.stream()
                .filter(m -> m.contains("Work.compute"))
                .count();
        assertTrue(computeCount >= 3,
                "All 3 chain stages should share RI, found " + computeCount);
    }

    // ==================== Scenario 5: ForkJoinPool.submit(Callable) ====================

    @Test
    void forkJoinSubmit_callableSharesRequestId() {
        long ri = singleRiFor("Scenarios.forkJoinSubmit");

        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1,
                "ForkJoin callable should run on pool thread, got: " + threads);
    }

    // ==================== Scenario 6: Nested executors ====================

    @Test
    void nestedExecutors_allLayersShareRequestId() {
        long ri = singleRiFor("Scenarios.nestedExecutors");

        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() >= 3,
                "Should span main + outer pool + inner pool, got: " + threads);
    }

    // ==================== Scenario 7: Sequential roots ====================

    @Test
    void sequentialRoots_getDifferentRequestIds() {
        long ri1 = singleRiFor("Scenarios.sequentialRoot1");
        long ri2 = singleRiFor("Scenarios.sequentialRoot2");
        assertNotEquals(ri1, ri2,
                "Sequential root calls should have different request IDs");
    }

    // ==================== Scenario 8: Parallel independent roots ====================

    @Test
    void parallelIndependentRoots_getDifferentRequestIds() {
        long ri1 = singleRiFor("Scenarios.independentRoot1");
        long ri2 = singleRiFor("Scenarios.independentRoot2");
        assertNotEquals(ri1, ri2,
                "Independent roots on different threads should have different RIs");
    }

    @Test
    void parallelIndependentRoots_runOnNamedThreads() {
        long ri1 = singleRiFor("Scenarios.independentRoot1");
        long ri2 = singleRiFor("Scenarios.independentRoot2");

        Set<String> t1 = traces.threadsForRequestId(ri1);
        Set<String> t2 = traces.threadsForRequestId(ri2);

        assertTrue(t1.stream().anyMatch(t -> t.contains("indie-thread")),
                "independentRoot1 should run on indie-thread, got: " + t1);
        assertTrue(t2.stream().anyMatch(t -> t.contains("indie-thread")),
                "independentRoot2 should run on indie-thread, got: " + t2);
    }

    // ==================== Caller line ====================

    @Test
    void callerLine_capturesActualCallerNotTargetBody() {
        long ri1 = singleRiFor("Scenarios.independentRoot1");
        long ri2 = singleRiFor("Scenarios.independentRoot2");

        String cl1 = workDoWorkBlock(ri1).tags().get("CL");
        String cl2 = workDoWorkBlock(ri2).tags().get("CL");

        // independentRoot1 and independentRoot2 each call work.doWork(...) at
        // different source lines. If CL were the target's body line, both
        // would report the same value (Work.doWork's first body line). They
        // differ when CL captures the caller's line, which is the contract.
        assertNotEquals(cl1, cl2,
                "CL should reflect the call site, not the target method body. "
                        + "Both reported " + cl1);
    }

    private TraceBlock workDoWorkBlock(long ri) {
        return traces.entries().stream()
                .filter(b -> b.requestId() == ri)
                .filter(b -> b.method() != null && b.method().contains("Work.doWork"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No Work.doWork entry block for RI=" + ri));
    }

    // ==================== Helpers ====================

    private long singleRiFor(String methodSubstring) {
        Set<Long> ids = traces.requestIdsForMethod(methodSubstring);
        assertEquals(1, ids.size(),
                "Expected exactly 1 request ID for " + methodSubstring + ", got " + ids);
        return ids.iterator().next();
    }
}
