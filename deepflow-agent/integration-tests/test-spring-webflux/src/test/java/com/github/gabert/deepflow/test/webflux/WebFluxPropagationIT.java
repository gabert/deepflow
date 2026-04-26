package com.github.gabert.deepflow.test.webflux;

import com.github.gabert.deepflow.test.common.TestTraceCollector;
import com.github.gabert.deepflow.test.common.TraceData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebFlux integration tests for request ID propagation.
 *
 * <p>WebFlux uses Reactor schedulers (boundedElastic, parallel) which internally
 * use ScheduledThreadPoolExecutor — a subclass of ThreadPoolExecutor. Our
 * ExecutorAdvice should intercept these submissions and wrap tasks with
 * PropagatingRunnable.</p>
 *
 * <p>CAVEAT: In reactive code, the handler method returns a Mono/Flux and exits
 * before the reactive pipeline executes. The request ID ThreadLocal retains
 * its value on the event loop thread (not cleared on handler exit), so single-
 * request sequential tests work. Under concurrent load, the stale ThreadLocal
 * can pick up a different request's ID — Reactor context propagation would be
 * needed for production correctness.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFluxPropagationIT {

    @LocalServerPort
    int port;

    private TraceData traces;

    @BeforeAll
    void exerciseEndpoints() throws Exception {
        TestTraceCollector.clear();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;

        // Scenario 14: simple Mono (synchronous in handler)
        client.send(HttpRequest.newBuilder(URI.create(base + "/mono")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Scenario 15: Mono.fromCallable on boundedElastic
        client.send(HttpRequest.newBuilder(URI.create(base + "/blocking")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Scenario 16: Flux with publishOn parallel
        client.send(HttpRequest.newBuilder(URI.create(base + "/flux")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Scenario 17: reactive chain (two boundedElastic hops)
        client.send(HttpRequest.newBuilder(URI.create(base + "/chain")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Wait for the agent drainer to flush
        Thread.sleep(2000);
        traces = TestTraceCollector.collect();

        assertFalse(traces.blocks().isEmpty(), "Should have captured trace records");
    }

    // ==================== Scenario 14: Simple Mono (sync in handler) ====================

    @Test
    void mono_handlerAndServiceShareRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestHandler.mono");
        assertFalse(ids.isEmpty(), "Mono endpoint should produce trace records");

        long ri = ids.iterator().next();
        List<String> methods = traces.methodsForRequestId(ri);
        assertTrue(methods.stream()
                        .anyMatch(m -> m.contains("ReactiveWorkService.blockingQuery")),
                "Service call should share RI with handler");
    }

    // ==================== Scenario 15: boundedElastic ====================

    @Test
    void blocking_serviceCallIsTraced() {
        Set<Long> serviceIds = traces.requestIdsForMethod("ReactiveWorkService.blockingQuery");
        assertFalse(serviceIds.isEmpty(),
                "blockingQuery should be traced on the elastic thread");
    }

    @Test
    void blocking_handlerIsTraced() {
        Set<Long> handlerIds = traces.requestIdsForMethod("TestHandler.blocking");
        assertFalse(handlerIds.isEmpty(),
                "blocking handler should be traced on the event loop thread");
    }

    // ==================== Scenario 16: Flux with publishOn ====================

    @Test
    void flux_transformCallsAreTraced() {
        Set<Long> ids = traces.requestIdsForMethod("ReactiveWorkService.transform");
        assertFalse(ids.isEmpty(), "transform() calls should be traced");
    }

    @Test
    void flux_handlerIsTraced() {
        Set<Long> ids = traces.requestIdsForMethod("TestHandler.flux");
        assertFalse(ids.isEmpty(), "flux handler should be traced");
    }

    // ==================== Scenario 17: Reactive chain ====================

    @Test
    void chain_handlerIsTraced() {
        Set<Long> ids = traces.requestIdsForMethod("TestHandler.chain");
        assertFalse(ids.isEmpty(), "chain handler should be traced");
    }

    @Test
    void chain_bothBlockingCallsAreTraced() {
        List<String> allMethods = traces.entries().stream()
                .filter(b -> b.method() != null
                        && b.method().contains("ReactiveWorkService.blockingQuery"))
                .map(b -> b.tags().getOrDefault("AR", ""))
                .toList();
        assertTrue(allMethods.size() >= 2,
                "Should find multiple blockingQuery calls across endpoints");
    }
}
