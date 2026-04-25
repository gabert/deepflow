package com.github.gabert.deepflow.agent;

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
 * End-to-end tests for DeepFlowAdvice recording logic.
 *
 * Calls recordEntry/recordExit directly (no ByteBuddy, no javaagent),
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
    private Method voidMethod;
    private Method intMethod;
    private Method objectMethod;

    @BeforeEach
    void setUp() throws Exception {
        buffer = new UnboundedRecordBuffer();
        DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0] = 0L;
        DeepFlowAdvice.DEPTH.get()[0] = 0;
        DeepFlowAdvice.RECORD_BUFFER = buffer;

        configureAdvice("serialize_values=true&expand_this=false");
        setField("JPA_PROXY_RESOLVER_INITIALIZED", true);
        setField("SESSION_ID_RESOLVER", NOOP_RESOLVER);

        voidMethod = ArrayList.class.getMethod("clear");
        intMethod = ArrayList.class.getMethod("size");
        objectMethod = HashMap.class.getMethod("get", Object.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        DeepFlowAdvice.RECORD_BUFFER = null;
        DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0] = 0L;
        DeepFlowAdvice.DEPTH.get()[0] = 0;
        setField("SESSION_ID_RESOLVER", null);
    }

    // ==================== BASIC RECORDING ====================

    @Test
    void voidMethodRecordsEntryAndExit() {
        String threadName = Thread.currentThread().getName();
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

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
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 42, null, new Object[]{});

        renderNext();
        List<String> exit = renderNext();
        assertEquals("VALUE", findTag(exit, "RT"));
        assertNotNull(findTag(exit, "RE"), "Exit should have RE for return value");
    }

    @Test
    void exceptionRecorded() {
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, null, new RuntimeException("test error"), new Object[]{});

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
        DeepFlowAdvice.recordEntry(objectMethod, new HashMap<>(), args);
        DeepFlowAdvice.recordExit(objectMethod, null, null, args);

        List<String> entry = renderNext();
        String ar = findTag(entry, "AR");
        assertNotNull(ar);
        assertTrue(ar.contains("hello"));
    }

    @Test
    void staticMethodHasNoThisInstance() throws Exception {
        Method staticMethod = Collections.class.getMethod("emptyList");
        DeepFlowAdvice.recordEntry(staticMethod, null, new Object[]{});
        DeepFlowAdvice.recordExit(staticMethod, List.of(), null, new Object[]{});

        List<String> entry = renderNext();
        assertFalse(hasTag(entry, "TI"), "Static method (self=null) should not have TI");
    }

    @Test
    void thisInstanceRefWhenExpandFalse() {
        Object self = new ArrayList<>(List.of("a", "b"));
        DeepFlowAdvice.recordEntry(intMethod, self, new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 2, null, new Object[]{});

        List<String> entry = renderNext();
        String ti = findTag(entry, "TI");
        assertNotNull(ti, "Instance method should have TI");
        assertDoesNotThrow(() -> Long.parseLong(ti), "TI should be numeric object ref ID");
    }

    @Test
    void thisInstanceExpandedWhenExpandTrue() throws Exception {
        configureAdvice("serialize_values=true&expand_this=true");

        Map<String, String> self = new HashMap<>(Map.of("field", "value"));
        DeepFlowAdvice.recordEntry(objectMethod, self, new Object[]{"key"});
        DeepFlowAdvice.recordExit(objectMethod, null, null, new Object[]{"key"});

        List<String> entry = renderNext();
        String ti = findTag(entry, "TI");
        assertNotNull(ti, "Instance method should have TI");
        assertThrows(NumberFormatException.class, () -> Long.parseLong(ti),
                "Expanded TI should NOT be a simple number");
    }

    // ==================== REQUEST ID CORRELATION ====================

    @Test
    void entryAndExitShareSameRequestId() {
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});

        String entryRI = findTag(renderNext(), "RI");
        String exitRI = findTag(renderNext(), "RI");
        assertNotNull(entryRI);
        assertEquals(entryRI, exitRI, "Entry and exit must share the same request ID");
    }

    @Test
    void nestedCallsShareSameRequestId() {
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

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
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});
        String firstRI = findTag(renderNext(), "RI");
        renderNext();

        // Second root call — depth back to 0, new request ID
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});
        String secondRI = findTag(renderNext(), "RI");

        assertNotEquals(firstRI, secondRI);
    }

    @Test
    void deepNestingMaintainsSameRequestId() {
        int levels = 5;
        for (int i = 0; i < levels; i++) {
            DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        }
        for (int i = 0; i < levels; i++) {
            DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
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
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

        String req1RI = findTag(renderNext(), "RI");
        renderNext(); renderNext(); renderNext();

        // Request 2 on same thread — depth is back to 0
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

        String req2RI = findTag(renderNext(), "RI");
        assertNotEquals(req1RI, req2RI, "Reused thread must generate new request ID per root call");
    }

    // ==================== CROSS-THREAD PROPAGATION ====================

    @Test
    void propagatedTaskSharesParentRequestId() throws Exception {
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        long parentRequestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0];

        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new PropagatingRunnable(() -> {
            DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
            latch.countDown();
        }, parentRequestId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();

        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

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
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), new Object[]{});
        long parentRequestId = DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0];

        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new PropagatingRunnable(() -> {
            // Two nested calls in child thread
            DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            DeepFlowAdvice.recordEntry(objectMethod, new HashMap<>(), new Object[]{"k"});
            DeepFlowAdvice.recordExit(objectMethod, null, null, new Object[]{"k"});
            DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
            latch.countDown();
        }, parentRequestId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(task);
        latch.await();
        executor.shutdown();
        DeepFlowAdvice.recordExit(voidMethod, null, null, new Object[]{});

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
            DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            thread1Id.set(DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0]);
            DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
            doneLatch.countDown();
        });
        Thread t2 = new Thread(() -> {
            awaitQuietly(startLatch);
            DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
            thread2Id.set(DeepFlowAdvice.CURRENT_REQUEST_ID.get()[0]);
            DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});
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

        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{"arg"});
        DeepFlowAdvice.recordExit(intMethod, 42, null, new Object[]{"arg"});

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
        DeepFlowAdvice.recordEntry(intMethod, self, new Object[]{"arg"});
        DeepFlowAdvice.recordExit(intMethod, 42, null, new Object[]{"arg"});

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
        DeepFlowAdvice.recordEntry(voidMethod, new ArrayList<>(), args);
        DeepFlowAdvice.recordExit(voidMethod, null, null, args);

        renderNext();
        List<String> exit = renderNext();
        assertTrue(hasTag(exit, "AX"), "AX should be emitted when enabled");
        assertTrue(findTag(exit, "AX").contains("mutable"));
    }

    // ==================== SESSION ID ====================

    @Test
    void sessionIdRecordedWhenResolverReturnsValue() throws Exception {
        setField("SESSION_ID_RESOLVER", null);
        configureAdvice("serialize_values=true&expand_this=false&session_resolver=test");

        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});

        List<String> entry = renderNext();
        assertEquals("test-session-123", findTag(entry, "SI"));
    }

    @Test
    void noSessionIdWhenResolverReturnsNull() {
        DeepFlowAdvice.recordEntry(intMethod, new ArrayList<>(), new Object[]{});
        DeepFlowAdvice.recordExit(intMethod, 0, null, new Object[]{});

        assertFalse(hasTag(renderNext(), "SI"), "No SI when resolver returns null");
    }

    // ==================== HELPERS ====================

    private void configureAdvice(String agentArgs) throws Exception {
        AgentConfig config = AgentConfig.getInstance(agentArgs);
        DeepFlowAdvice.CONFIG = config;
        setField("SERIALIZE_VALUES", config.isSerializeValues());
        setField("EXPAND_THIS", config.isExpandThis());
        setField("EMIT_TI", config.shouldEmit("TI"));
        setField("EMIT_AR", config.shouldEmit("AR"));
        setField("EMIT_RT", config.shouldEmit("RT") || config.shouldEmit("RE"));
        setField("EMIT_AX", config.shouldEmit("AX"));
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

    private static void setField(String name, Object value) throws Exception {
        Field field = DeepFlowAdvice.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
