package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.advice.DeepFlowAdvice;
import com.github.gabert.deepflow.agent.bootstrap.PropagatingRunnable;
import com.github.gabert.deepflow.agent.bootstrap.RequestContext;
import com.github.gabert.deepflow.agent.recording.RequestRecorder;
import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import com.github.gabert.deepflow.recorder.buffer.UnboundedRecordBuffer;
import com.github.gabert.deepflow.recorder.destination.RecordRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the recording logic.
 *
 * Calls RequestRecorder.recordEntry/recordExit directly (no ByteBuddy, no javaagent),
 * captures binary records via an injected buffer, and verifies rendered output.
 *
 * Coverage:
 *   - Basic recording (void, value return, exception, arguments, this instance)
 *   - Request ID correlation (entry/exit, nesting, sequential, deep nesting)
 *   - Thread pool reuse (depth resets, new request ID per root call)
 *   - Cross-thread propagation (PropagatingRunnable, independent threads)
 *   - Configuration modes (serialize_values, emit_tags, expand_this, AX)
 *   - Session ID (resolver present vs absent)
 */
class DeepFlowAdviceRecordingTest {

    private static final SessionIdResolver NOOP_RESOLVER = new SessionIdResolver() {
        @Override public String name() { return "test-noop"; }
        @Override public String resolve() { return null; }
    };

    private UnboundedRecordBuffer buffer;
    private RequestRecorder recorder;
    private Method voidMethod;
    private Method intMethod;
    private Method objectMethod;

    @BeforeEach
    void setUp() throws Exception {
        buffer = new UnboundedRecordBuffer();
        RequestContext.CURRENT_REQUEST_ID.get()[0] = 0L;
        RequestContext.DEPTH.get()[0] = 0;

        configureAdvice("serialize_values=true&expand_this=false");
        setRecorderField("jpaProxyResolverInitialized", true);
        setRecorderField("sessionIdResolver", NOOP_RESOLVER);

        voidMethod = ArrayList.class.getMethod("clear");
        intMethod = ArrayList.class.getMethod("size");
        objectMethod = HashMap.class.getMethod("get", Object.class);
    }

    @AfterEach
    void tearDown() {
        DeepFlowAdvice.RECORDER = null;
        RequestContext.CURRENT_REQUEST_ID.get()[0] = 0L;
        RequestContext.DEPTH.get()[0] = 0;
    }

    // ==================== BASIC RECORDING ====================

    @Test
    void voidMethodRecordsEntryAndExit() {
        String threadName = Thread.currentThread().getName();
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});

        List<String> entry = renderNext();
        assertTrue(hasTag(entry, "MS"));
        assertTrue(findTag(entry, "MS").contains("ArrayList.clear"));
        assertTrue(hasTag(entry, "TS"));
        assertEquals(threadName, findTag(entry, "TN"));
        assertTrue(hasTag(entry, "RI"));
        assertTrue(Integer.parseInt(findTag(entry, "CL")) > 0, "Caller line should be positive");
        assertTrue(hasTag(entry, "AR"));

        List<String> exit = renderNext();
        assertTrue(hasTag(exit, "TE"));
        assertEquals(threadName, findTag(exit, "TN"));
        assertTrue(hasTag(exit, "RI"));
        assertEquals("VOID", findTag(exit, "RT"));
        assertFalse(hasTag(exit, "RE"));

        assertBufferEmpty();
    }

    @Test
    void valueReturnRecorded() {
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 42, null, new Object[]{});

        renderNext();
        List<String> exit = renderNext();
        assertEquals("VALUE", findTag(exit, "RT"));
        assertNotNull(findTag(exit, "RE"), "Exit should have RE for return value");
    }

    @Test
    void exceptionRecorded() {
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, null, new RuntimeException("test error"), new Object[]{});

        renderNext();
        List<String> exit = renderNext();
        assertEquals("EXCEPTION", findTag(exit, "RT"));
        String re = findTag(exit, "RE");
        assertNotNull(re);
        assertTrue(re.contains("test error"), "RE should contain exception message");
    }

    @Test
    void argumentsSerialized() {
        Object[] args = {"hello", 42, true};
        recorder.recordEntry(objectMethod, new HashMap<>(), args);
        recorder.recordExit(objectMethod, null, null, args);

        List<String> entry = renderNext();
        String ar = findTag(entry, "AR");
        assertNotNull(ar);
        assertTrue(ar.contains("hello"));
    }

    @Test
    void staticMethodHasNoThisInstance() throws Exception {
        Method staticMethod = Collections.class.getMethod("emptyList");
        recorder.recordEntry(staticMethod, null, new Object[]{});
        recorder.recordExit(staticMethod, List.of(), null, new Object[]{});

        List<String> entry = renderNext();
        assertFalse(hasTag(entry, "TI"), "Static method (self=null) should not have TI");
    }

    @Test
    void thisInstanceRefWhenExpandFalse() {
        Object self = new ArrayList<>(List.of("a", "b"));
        recorder.recordEntry(intMethod, self, new Object[]{});
        recorder.recordExit(intMethod, 2, null, new Object[]{});

        List<String> entry = renderNext();
        String ti = findTag(entry, "TI");
        assertNotNull(ti, "Instance method should have TI");
        assertDoesNotThrow(() -> Long.parseLong(ti), "TI should be numeric object ref ID");
    }

    @Test
    void thisInstanceExpandedWhenExpandTrue() throws Exception {
        configureAdvice("serialize_values=true&expand_this=true");

        Map<String, String> self = new HashMap<>(Map.of("field", "value"));
        recorder.recordEntry(objectMethod, self, new Object[]{"key"});
        recorder.recordExit(objectMethod, null, null, new Object[]{"key"});

        List<String> entry = renderNext();
        String ti = findTag(entry, "TI");
        assertNotNull(ti, "Instance method should have TI");
        assertThrows(NumberFormatException.class, () -> Long.parseLong(ti),
                "Expanded TI should NOT be a simple number");
    }

    // ==================== REQUEST ID CORRELATION ====================

    @Test
    void entryAndExitShareSameRequestId() {
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 0, null, new Object[]{});

        String entryRI = findTag(renderNext(), "RI");
        String exitRI = findTag(renderNext(), "RI");
        assertNotNull(entryRI);
        assertEquals(entryRI, exitRI, "Entry and exit must share the same request ID");
    }

    @Test
    void nestedCallsShareSameRequestId() {
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 0, null, new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});

        String rootEntryRI = findTag(renderNext(), "RI");
        String nestedEntryRI = findTag(renderNext(), "RI");
        String nestedExitRI = findTag(renderNext(), "RI");
        String rootExitRI = findTag(renderNext(), "RI");

        assertEquals(rootEntryRI, nestedEntryRI);
        assertEquals(rootEntryRI, nestedExitRI);
        assertEquals(rootEntryRI, rootExitRI);
        assertBufferEmpty();
    }

    @Test
    void sequentialRootCallsGetDifferentRequestIds() {
        // First root call
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});
        String firstRI = findTag(renderNext(), "RI");
        renderNext();

        // Second root call — depth back to 0, new request ID
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});
        String secondRI = findTag(renderNext(), "RI");

        assertNotEquals(firstRI, secondRI);
    }

    @Test
    void deepNestingMaintainsSameRequestId() {
        int levels = 5;
        for (int i = 0; i < levels; i++) {
            recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        }
        for (int i = 0; i < levels; i++) {
            recorder.recordExit(intMethod, 0, null, new Object[]{});
        }

        String firstRI = findTag(renderNext(), "RI");
        for (int i = 1; i < levels * 2; i++) {
            assertEquals(firstRI, findTag(renderNext(), "RI"),
                    "Record " + i + " should share request ID with root");
        }
        assertBufferEmpty();
    }

    // ==================== THREAD POOL REUSE ====================

    @Test
    void sameThreadSequentialRequestsGetDifferentIds() {
        // Request 1: root + nested
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 0, null, new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});

        String req1RI = findTag(renderNext(), "RI");
        renderNext(); renderNext(); renderNext();

        // Request 2 on same thread — depth is back to 0
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(voidMethod, null, null, new Object[]{});

        String req2RI = findTag(renderNext(), "RI");
        assertNotEquals(req1RI, req2RI, "Reused thread must generate new request ID per root call");
    }

    // ==================== CROSS-THREAD PROPAGATION ====================

    @Test
    void propagatedTaskSharesParentRequestId() throws Exception {
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        long parentRequestId = RequestContext.CURRENT_REQUEST_ID.get()[0];

        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new PropagatingRunnable(() -> {
            recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            recorder.recordExit(intMethod, 0, null, new Object[]{});
            latch.countDown();
        }, parentRequestId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        recorder.recordExit(voidMethod, null, null, new Object[]{});

        // Buffer order: parent entry, child entry, child exit, parent exit
        String parentEntryRI = findTag(renderNext(), "RI");
        String childEntryRI = findTag(renderNext(), "RI");
        String childExitRI = findTag(renderNext(), "RI");
        String parentExitRI = findTag(renderNext(), "RI");

        assertEquals(parentEntryRI, childEntryRI, "Child must inherit parent request ID");
        assertEquals(parentEntryRI, childExitRI);
        assertEquals(parentEntryRI, parentExitRI);
        assertBufferEmpty();
    }

    @Test
    void propagatedNestedCallsShareRequestId() throws Exception {
        recorder.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        long parentRequestId = RequestContext.CURRENT_REQUEST_ID.get()[0];

        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new PropagatingRunnable(() -> {
            // Two nested calls in child thread
            recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            recorder.recordEntry(objectMethod, new HashMap<>(), new Object[]{"k"});
            recorder.recordExit(objectMethod, null, null, new Object[]{"k"});
            recorder.recordExit(intMethod, 0, null, new Object[]{});
            latch.countDown();
        }, parentRequestId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();
        recorder.recordExit(voidMethod, null, null, new Object[]{});

        // All 6 records (3 entries + 3 exits) should share the same RI
        String expectedRI = findTag(renderNext(), "RI");
        for (int i = 1; i < 6; i++) {
            assertEquals(expectedRI, findTag(renderNext(), "RI"),
                    "Record " + i + " should share propagated request ID");
        }
        assertBufferEmpty();
    }

    @Test
    void multipleThreadsGetIndependentRequestIds() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicLong thread1Id = new AtomicLong();
        AtomicLong thread2Id = new AtomicLong();

        Thread t1 = new Thread(() -> {
            awaitQuietly(startLatch);
            recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            thread1Id.set(RequestContext.CURRENT_REQUEST_ID.get()[0]);
            recorder.recordExit(intMethod, 0, null, new Object[]{});
            doneLatch.countDown();
        });
        Thread t2 = new Thread(() -> {
            awaitQuietly(startLatch);
            recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            thread2Id.set(RequestContext.CURRENT_REQUEST_ID.get()[0]);
            recorder.recordExit(intMethod, 0, null, new Object[]{});
            doneLatch.countDown();
        });

        t1.start();
        t2.start();
        startLatch.countDown();
        doneLatch.await();

        assertNotEquals(thread1Id.get(), thread2Id.get(),
                "Independent threads must get different request IDs");
        assertEquals(4, buffer.size());
    }

    // ==================== CONFIGURATION MODES ====================

    @Test
    void serializeValuesFalseEmitsOnlyStructuralRecords() throws Exception {
        configureAdvice("serialize_values=false");

        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{"arg"});
        recorder.recordExit(intMethod, 42, null, new Object[]{"arg"});

        List<String> entry = renderNext();
        assertTrue(hasTag(entry, "MS"));
        assertTrue(hasTag(entry, "TS"));
        assertTrue(hasTag(entry, "TN"));
        assertTrue(hasTag(entry, "RI"));
        assertTrue(hasTag(entry, "CL"));
        assertFalse(hasTag(entry, "AR"), "No AR in structural-only mode");
        assertFalse(hasTag(entry, "TI"), "No TI in structural-only mode");

        List<String> exit = renderNext();
        assertTrue(hasTag(exit, "TE"));
        assertTrue(hasTag(exit, "TN"));
        assertTrue(hasTag(exit, "RI"));
        assertFalse(hasTag(exit, "RT"), "No RT in structural-only mode");
        assertFalse(hasTag(exit, "RE"), "No RE in structural-only mode");
        assertBufferEmpty();
    }

    @Test
    void disabledEmitTagsSkipSerialization() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&emit_tags=TS,TE");

        Object self = new ArrayList<>(List.of("data"));
        recorder.recordEntry(intMethod, self, new Object[]{"arg"});
        recorder.recordExit(intMethod, 42, null, new Object[]{"arg"});

        List<String> entry = renderNext();
        assertTrue(hasTag(entry, "MS"), "MS is always emitted");
        assertTrue(hasTag(entry, "TS"));
        assertFalse(hasTag(entry, "AR"), "AR disabled — args not serialized");
        assertFalse(hasTag(entry, "TI"), "TI disabled — this not serialized");

        List<String> exit = renderNext();
        assertTrue(hasTag(exit, "TE"));
        assertEquals("VOID", findTag(exit, "RT"),
                "EMIT_RT=false — agent writes void stub regardless of actual return");
        assertFalse(hasTag(exit, "RE"), "RE disabled — return value not serialized");
    }

    @Test
    void argumentsAtExitWhenAXEnabled() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false"
                + "&emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,AX");

        Object[] args = {"mutable"};
        recorder.recordEntry(voidMethod, new ArrayList<>(), args);
        recorder.recordExit(voidMethod, null, null, args);

        renderNext();
        List<String> exit = renderNext();
        assertTrue(hasTag(exit, "AX"), "AX should be emitted when enabled");
        assertTrue(findTag(exit, "AX").contains("mutable"));
    }

    // ==================== SESSION ID ====================

    @Test
    void sessionIdRecordedWhenResolverReturnsValue() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&session_resolver=test");
        // Leave sessionIdResolver unset on the new recorder so lazy init via
        // SpiLoader resolves the configured "test" SPI from the test classpath.

        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 0, null, new Object[]{});

        List<String> entry = renderNext();
        assertEquals("test-session-123", findTag(entry, "SI"));
    }

    @Test
    void noSessionIdWhenResolverReturnsNull() {
        recorder.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        recorder.recordExit(intMethod, 0, null, new Object[]{});

        assertFalse(hasTag(renderNext(), "SI"), "No SI when resolver returns null");
    }

    // ==================== TRUNCATION ====================

    @Test
    void largeReturnValueTruncated() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&max_value_size=64");

        recorder.recordEntry(objectMethod, new HashMap<>(), new Object[]{"key"});
        String bigValue = "x".repeat(500);
        recorder.recordExit(objectMethod, bigValue, null, new Object[]{"key"});

        renderNext();
        List<String> exit = renderNext();
        assertEquals("VALUE", findTag(exit, "RT"));
        String re = findTag(exit, "RE");
        assertTrue(re.contains("__truncated"), "Oversized return should show __truncated marker");
        assertTrue(re.contains("original_size"), "Truncation marker should include original_size");
    }

    @Test
    void smallReturnValueNotTruncated() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&max_value_size=64000");

        recorder.recordEntry(objectMethod, new HashMap<>(), new Object[]{"key"});
        recorder.recordExit(objectMethod, "short", null, new Object[]{"key"});

        renderNext();
        List<String> exit = renderNext();
        String re = findTag(exit, "RE");
        assertFalse(re.contains("__truncated"), "Small value should not be truncated");
        assertTrue(re.contains("short"));
    }

    @Test
    void largeArgumentsTruncated() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&max_value_size=64");

        String bigArg = "y".repeat(500);
        recorder.recordEntry(objectMethod, new HashMap<>(), new Object[]{bigArg});
        recorder.recordExit(objectMethod, null, null, new Object[]{bigArg});

        List<String> entry = renderNext();
        String ar = findTag(entry, "AR");
        assertTrue(ar.contains("__truncated"), "Oversized arguments should show __truncated marker");
    }

    @Test
    void noTruncationWhenDisabled() throws Exception {
        configureAdvice("serialize_values=true&expand_this=false&max_value_size=0");

        String bigValue = "z".repeat(500);
        recorder.recordEntry(objectMethod, new HashMap<>(), new Object[]{bigValue});
        recorder.recordExit(objectMethod, bigValue, null, new Object[]{bigValue});

        List<String> entry = renderNext();
        assertFalse(findTag(entry, "AR").contains("__truncated"),
                "No truncation when max_value_size=0");

        List<String> exit = renderNext();
        assertFalse(findTag(exit, "RE").contains("__truncated"),
                "No truncation when max_value_size=0");
    }

    // ==================== HELPERS ====================

    private void configureAdvice(String agentArgs) throws Exception {
        AgentConfig config = AgentConfig.getInstance(agentArgs);
        recorder = new RequestRecorder(buffer, config);
        DeepFlowAdvice.RECORDER = recorder;
    }

    private List<String> renderNext() {
        byte[] data = buffer.poll();
        assertNotNull(data, "Expected record in buffer but it was empty");
        return RecordRenderer.render(data).lines();
    }

    private static String findTag(List<String> lines, String tag) {
        return lines.stream()
                .filter(l -> l.startsWith(tag + ";"))
                .findFirst()
                .map(l -> l.substring(tag.length() + 1))
                .orElse(null);
    }

    private static boolean hasTag(List<String> lines, String tag) {
        return lines.stream().anyMatch(l -> l.startsWith(tag + ";"));
    }

    private void assertBufferEmpty() {
        assertNull(buffer.poll(), "Expected no more records in buffer");
    }

    private void setRecorderField(String name, Object value) throws Exception {
        Field field = RequestRecorder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(recorder, value);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
