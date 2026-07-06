package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.codec.Hasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClickHouseSink}. The sink talks to ClickHouse over
 * HTTP in production; here we test pure logic only by:
 * <ul>
 *   <li>overriding {@link ClickHouseSink#flush()} to a no-op so the
 *       periodic flusher does not clear buffers under the test's feet, and</li>
 *   <li>asserting buffer state via the package-private accessors (mirroring
 *       the openCallCount() pattern used by RecordParser).</li>
 * </ul>
 *
 * <p>Integration with ClickHouse itself (HTTP, JSONEachRow round-trip,
 * insert-on-flush) is out of scope for this unit-level suite.</p>
 */
class ClickHouseSinkTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CALL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARENT_CALL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private TestSink sink;

    @BeforeEach
    void setUp() {
        ProcessorConfig config = new ProcessorConfig(Map.of(
                "clickhouse_url", "http://localhost:1"));
        sink = new TestSink(config);
    }

    @AfterEach
    void tearDown() {
        if (sink != null) sink.close();
    }

    // ============================================================
    //  accept() — agent-run row buffering, deduped per run id
    // ============================================================

    @Test
    void acceptBuffersOneAgentRunRowPerDistinctRunId() {
        // A run spanning many batches buffers one row per flush window;
        // ReplacingMergeTree collapses re-emits across flushes/restarts.
        sink.accept(List.of(), agentRun(RUN_ID));
        sink.accept(List.of(), agentRun(RUN_ID));
        assertEquals(1, sink.agentRunBufferSize(),
                "same run id across batches must not multiply rows");

        sink.accept(List.of(), agentRun(OTHER_RUN_ID));
        assertEquals(2, sink.agentRunBufferSize(),
                "a different run id buffers its own row");
    }

    // ============================================================
    //  noteSessionIfNew — dedup keyed on (runId, sessionId)
    // ============================================================

    @Test
    void noteSessionIfNewDedupsSameRunIdAndSessionId() {
        sink.noteSessionIfNew(call("session-a"), RUN_ID);
        sink.noteSessionIfNew(call("session-a"), RUN_ID);
        assertEquals(1, sink.sessionBufferSize(),
                "second call with same (runId, sessionId) must not add a row");
        assertEquals(1, sink.seenSessionCount());
    }

    @Test
    void noteSessionIfNewEmitsDistinctRowsForDifferentSessionIds() {
        sink.noteSessionIfNew(call("session-a"), RUN_ID);
        sink.noteSessionIfNew(call("session-b"), RUN_ID);
        assertEquals(2, sink.sessionBufferSize());
        assertEquals(2, sink.seenSessionCount());
    }

    @Test
    void noteSessionIfNewKeysOnRunIdNotJustSessionId() {
        // Same session-id under two different agent runs must produce two
        // session rows. Cross-run reuse of a session id is plausible (config-
        // based session resolver), and the join into agent_runs has to stay
        // accurate per-run.
        sink.noteSessionIfNew(call("session-a"), RUN_ID);
        sink.noteSessionIfNew(call("session-a"), OTHER_RUN_ID);
        assertEquals(2, sink.sessionBufferSize());
    }

    @Test
    void noteSessionIfNewTreatsNullSessionIdAsEmptyString() {
        // Defensive: ParsedCall.sessionId can be null when the agent emits no
        // SI tag. The key uses "" so dedup still works.
        sink.noteSessionIfNew(call(null), RUN_ID);
        sink.noteSessionIfNew(call(null), RUN_ID);
        assertEquals(1, sink.sessionBufferSize());

        Map<String, Object> row = sink.peekSessions().get(0);
        assertEquals("", row.get("session_id"));
    }

    // ============================================================
    //  addRows — payload row count + this_id derivation
    // ============================================================

    @Test
    void staticMethodAddsNoTiPayloadRowAndNullThisId() {
        ParsedCall c = callBuilder().build();   // no thisIdRef, no thisJson, no AR/AX/RE
        sink.addRows(c, RUN_ID);

        assertEquals(1, sink.callBufferSize());
        assertEquals(0, sink.payloadBufferSize(),
                "static call with no values produces no payload rows");
        assertNull(sink.peekCalls().get(0).get("this_id"),
                "static method has no this — column is null");
    }

    @Test
    void thisIdRefOnlyAddsNoTiPayloadAndCarriesRefAsThisId() {
        ParsedCall c = callBuilder().thisIdRef(99L).build();
        sink.addRows(c, RUN_ID);

        assertEquals(0, sink.payloadBufferSize(),
                "expand_this=false case — TI is only a reference, no payload row");
        assertEquals(99L, sink.peekCalls().get(0).get("this_id"));
    }

    @Test
    void thisJsonAddsTiPayloadAndExtractsThisIdFromMeta() throws IOException {
        // expand_this=true case: TI carries the full hashed envelope. The TI
        // payload row goes to payloads; this_id on the calls row is read from
        // __meta__.id of that root envelope.
        String hashedJson = Hasher.hash("""
                {"object_id": 77, "class": "X", "v": 1}
                """);
        ParsedCall c = callBuilder().thisJson(hashedJson).build();

        sink.addRows(c, RUN_ID);

        assertEquals(1, sink.payloadBufferSize());
        assertEquals("TI", sink.peekPayloads().get(0).get("kind"));
        assertEquals(77L, sink.peekCalls().get(0).get("this_id"),
                "this_id on the calls row must be extracted from TI's __meta__.id");
    }

    @Test
    void payloadKindsArArExitAndReAreEachOneRow() throws IOException {
        String ar = Hasher.hash("[{\"object_id\": 1, \"class\": \"X\"}]");
        String ax = Hasher.hash("[{\"object_id\": 1, \"class\": \"X\", \"changed\": true}]");
        ParsedCall c = callBuilder().argsJson(ar).argsExitJson(ax).returnJson("42").build();
        sink.addRows(c, RUN_ID);

        assertEquals(3, sink.payloadBufferSize());
        List<String> kinds = sink.peekPayloads().stream()
                .map(row -> (String) row.get("kind"))
                .toList();
        assertTrue(kinds.contains("AR"));
        assertTrue(kinds.contains("AX"));
        assertTrue(kinds.contains("RE"));
    }

    @Test
    void callRowParentCallIdIsNullForRootCall() {
        ParsedCall c = callBuilder().build();   // no parent
        sink.addRows(c, RUN_ID);
        assertNull(sink.peekCalls().get(0).get("parent_call_id"),
                "root call must have null parent_call_id (CH stores as Nullable UUID)");
    }

    @Test
    void callRowParentCallIdIsStringForChildCall() {
        ParsedCall c = callBuilder().parentCallId(PARENT_CALL_ID).build();
        sink.addRows(c, RUN_ID);
        assertEquals(PARENT_CALL_ID.toString(),
                sink.peekCalls().get(0).get("parent_call_id"));
    }

    // ============================================================
    //  Static row builders — agentRunRow / payloadRow shape
    // ============================================================

    @Test
    void agentRunRowCarriesAllMetadataFields() {
        AgentRun m = new AgentRun(
                RUN_ID, "host-a", "0.0.3", "rev-abc", "staging",
                12345L, 1700000000000L);

        Map<String, Object> row = ClickHouseSink.agentRunRow(m);

        assertEquals(RUN_ID.toString(),  row.get("agent_run_id"));
        assertEquals("host-a",           row.get("hostname"));
        assertEquals(12345L,             row.get("process_pid"));
        assertEquals("0.0.3",            row.get("agent_version"));
        assertEquals("rev-abc",          row.get("code_version"));
        assertEquals("staging",          row.get("env"));
        assertNotNull(row.get("started_at"),  "started_at formatted as CH datetime");
        assertNull(row.get("ended_at"),       "ended_at remains null until run closes");
        assertEquals(false, row.get("completed_clean"));
    }

    @Test
    void agentRunRowNullStringsBecomeEmpty() {
        // ReplacingMergeTree columns are not nullable strings — convert to ""
        // so we don't blow up on JSONEachRow insert.
        AgentRun m = new AgentRun(RUN_ID, null, null, null, null, 0L, 0L);
        Map<String, Object> row = ClickHouseSink.agentRunRow(m);

        assertEquals("", row.get("hostname"));
        assertEquals("", row.get("agent_version"));
        assertEquals("", row.get("code_version"));
        assertEquals("", row.get("env"));
    }

    @Test
    void payloadRowExtractsObjectIdsAndOwnHashesAndTokensFromHashedJson() throws IOException {
        // payloadRow's whole job is to attach the indexed columns CH uses for
        // value/identity search. Verify the wiring: object_ids, own_hashes,
        // payload_tokens, root_hash all populated from the same hashed input.
        String hashedJson = Hasher.hash("""
                {"object_id": 5, "class": "Book", "title": "Hobbit"}
                """);

        ParsedCall c = callBuilder().build();
        Map<String, Object> row = ClickHouseSink.payloadRow(c, RUN_ID, "AR", hashedJson);

        assertEquals("AR", row.get("kind"));
        assertEquals(hashedJson, row.get("payload_json"));
        assertNotEquals("00000000000000000000000000000000", row.get("root_hash"),
                "root_hash must come from Hasher, not the empty sentinel");

        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) row.get("object_ids");
        assertEquals(List.of(5L), ids);

        @SuppressWarnings("unchecked")
        List<String> ownHashes = (List<String>) row.get("own_hashes");
        assertEquals(1, ownHashes.size(),
                "own_hashes aligned with object_ids (1 envelope → 1 own_hash slot)");

        @SuppressWarnings("unchecked")
        List<String> tokens = (List<String>) row.get("payload_tokens");
        assertTrue(tokens.contains("Hobbit"),
                "user scalar must land in payload_tokens for value-search");
    }

    // ============================================================
    //  Fixtures
    // ============================================================

    private static AgentRun agentRun(UUID runId) {
        return new AgentRun(runId, "host", "0.0.3", null, null, 0L, 0L);
    }

    private static ParsedCall call(String sessionId) {
        return callBuilder().sessionId(sessionId).build();
    }

    private static CallBuilder callBuilder() {
        return new CallBuilder();
    }

    private static final class CallBuilder {
        private UUID callId = CALL_ID;
        private UUID parentCallId;
        private String sessionId = "s1";
        private long requestId = 1L;
        private long tsIn = 1000L;
        private long tsOut = 2000L;
        private Long thisIdRef;
        private String thisJson;
        private String argsJson;
        private String argsExitJson;
        private String returnJson;

        CallBuilder sessionId(String s)     { this.sessionId = s; return this; }
        CallBuilder parentCallId(UUID id)   { this.parentCallId = id; return this; }
        CallBuilder thisIdRef(Long id)      { this.thisIdRef = id; return this; }
        CallBuilder thisJson(String j)      { this.thisJson = j; return this; }
        CallBuilder argsJson(String j)      { this.argsJson = j; return this; }
        CallBuilder argsExitJson(String j)  { this.argsExitJson = j; return this; }
        CallBuilder returnJson(String j)    { this.returnJson = j; return this; }

        ParsedCall build() {
            return new ParsedCall(callId, parentCallId, sessionId, requestId, "t",
                    tsIn, tsOut, "F.f()V", 1, "VOID",
                    thisIdRef, thisJson, argsJson, argsExitJson, returnJson, 0L);
        }
    }

    /**
     * Sink stub that swallows flushes — the test asserts on buffer state, so
     * the periodic flusher and the final close-flush must not clear or POST.
     */
    static final class TestSink extends ClickHouseSink {
        TestSink(ProcessorConfig config) { super(config); }
        @Override
        void flush() { /* no-op: tests inspect buffers, never flush */ }
    }
}
