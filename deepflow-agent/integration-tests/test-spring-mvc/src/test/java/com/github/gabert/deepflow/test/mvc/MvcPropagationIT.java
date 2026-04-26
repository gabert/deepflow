package com.github.gabert.deepflow.test.mvc;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MvcPropagationIT {

    @LocalServerPort
    int port;

    private TraceData traces;

    @BeforeAll
    void exerciseEndpoints() throws Exception {
        TestTraceCollector.clear();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;

        // Scenario 9: sync controller -> service
        client.send(HttpRequest.newBuilder(URI.create(base + "/sync")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Scenario 10: @Async fire-and-forget
        client.send(HttpRequest.newBuilder(URI.create(base + "/async-fire-and-forget"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Scenario 11: @Async with result
        client.send(HttpRequest.newBuilder(URI.create(base + "/async-with-result"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        // Scenario 12: fan-out (3 parallel @Async)
        client.send(HttpRequest.newBuilder(URI.create(base + "/fan-out"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        // Scenario 13: two concurrent requests (different RIs expected)
        var req1 = client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/sync")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var req2 = client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/sync")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        req1.get();
        req2.get();

        // Wait for the agent drainer to flush
        Thread.sleep(2000);
        traces = TestTraceCollector.collect();

        assertFalse(traces.blocks().isEmpty(), "Should have captured trace records");
    }

    // ==================== Scenario 9: Sync controller ====================

    @Test
    void sync_controllerAndServiceShareRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestController.sync");
        assertFalse(ids.isEmpty(), "Should find RIs for sync endpoint");

        boolean found = false;
        for (long ri : ids) {
            List<String> methods = traces.methodsForRequestId(ri);
            if (methods.stream().anyMatch(m -> m.contains("WorkService.syncWork"))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Controller and service should share a request ID");
    }

    // ==================== Scenario 10: @Async fire-and-forget ====================

    @Test
    void asyncFireAndForget_propagatesRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestController.asyncFireAndForget");
        assertFalse(ids.isEmpty());

        long ri = ids.iterator().next();
        List<String> methods = traces.methodsForRequestId(ri);
        assertTrue(methods.stream().anyMatch(m -> m.contains("WorkService.doHeavyWork")),
                "Async fire-and-forget should propagate RI to doHeavyWork");

        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1,
                "@Async should run on a different thread, got: " + threads);
    }

    // ==================== Scenario 11: @Async with result ====================

    @Test
    void asyncWithResult_propagatesRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestController.asyncWithResult");
        assertFalse(ids.isEmpty());

        long ri = ids.iterator().next();
        Set<String> threads = traces.threadsForRequestId(ri);
        assertTrue(threads.size() > 1,
                "@Async should span threads, got: " + threads);
    }

    // ==================== Scenario 12: Fan-out ====================

    @Test
    void fanOut_allAsyncCallsShareRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestController.fanOut");
        assertFalse(ids.isEmpty());

        long ri = ids.iterator().next();
        List<String> methods = traces.methodsForRequestId(ri);
        long heavyCount = methods.stream()
                .filter(m -> m.contains("WorkService.doHeavyWork"))
                .count();
        assertTrue(heavyCount >= 3,
                "All 3 fan-out tasks should share RI, found " + heavyCount);
    }

    // ==================== Scenario 13: Concurrent requests ====================

    @Test
    void concurrentRequests_getDifferentRequestIds() {
        Set<Long> syncIds = traces.requestIdsForMethod("TestController.sync");
        assertTrue(syncIds.size() >= 3,
                "Sync endpoint was called 3+ times, each should get its own RI, found "
                        + syncIds.size());
    }
}
