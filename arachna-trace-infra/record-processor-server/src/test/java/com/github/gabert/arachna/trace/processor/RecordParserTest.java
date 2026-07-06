package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.Codec;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsExitRecord;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsRecord;
import com.github.gabert.arachna.trace.recorder.record.ExceptionRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodEndRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodStartRecord;
import com.github.gabert.arachna.trace.recorder.record.ReturnRecord;
import com.github.gabert.arachna.trace.recorder.record.SequenceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import com.github.gabert.arachna.trace.recorder.record.VersionRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordParserTest {

    private static final UUID OUT  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INN  = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void singleCallProducesOneParsedCall() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "com.example.Foo.bar()V", "main", 1000, 42),
                new ThisInstanceRefRecord(7),
                args(Map.of("a", 1)),
                me(OUT, "main", 1500, 42),
                ReturnRecord.ofVoid());

        List<ParsedCall> calls = new RecordParser().parse(records);

        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals(OUT, c.callId());
        assertNull(c.parentCallId());
        assertEquals("sess-1", c.sessionId());
        assertEquals(42L, c.requestId());
        assertEquals("main", c.threadName());
        assertEquals(1000L, c.tsInMillis());
        assertEquals(1500L, c.tsOutMillis());
        assertEquals("com.example.Foo.bar()V", c.signature());
        assertEquals(1, c.callerLine());
        assertEquals("VOID", c.returnType());
        assertEquals(7L, c.thisIdRef());
        assertNull(c.thisJson());
        assertNotNull(c.argsJson());
    }

    @Test
    void nestedCallsAreEmittedInPostOrderAndCarryParentLink() {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A.outer()V", "t", 1000, 1),
                ms(INN, OUT, "B.inner()V", "t", 1100, 1),
                me(INN, "t", 1200, 1),
                ReturnRecord.ofVoid(),
                me(OUT, "t", 1300, 1),
                ReturnRecord.ofVoid());

        List<ParsedCall> calls = new RecordParser().parse(records);

        assertEquals(2, calls.size());
        ParsedCall inner = calls.get(0);
        assertEquals("B.inner()V", inner.signature());
        assertEquals(1100L, inner.tsInMillis());
        assertEquals(1200L, inner.tsOutMillis());
        assertEquals(INN, inner.callId());
        assertEquals(OUT, inner.parentCallId(), "inner's parent must be outer");

        ParsedCall outer = calls.get(1);
        assertEquals("A.outer()V", outer.signature());
        assertEquals(1000L, outer.tsInMillis());
        assertEquals(1300L, outer.tsOutMillis());
        assertEquals(OUT, outer.callId());
        assertNull(outer.parentCallId(), "outer is the root — no parent");
    }

    @Test
    void valueReturnIsCaptured() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.f()I", "t", 1, 1),
                me(OUT, "t", 2, 1),
                new ReturnRecord(Codec.encode(42)));

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
    }

    @Test
    void exceptionReturnIsCaptured() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.f()V", "t", 1, 1),
                me(OUT, "t", 2, 1),
                new ExceptionRecord(Codec.encode(Map.of("message", "boom"))));

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertEquals("EXCEPTION", c.returnType());
        assertNotNull(c.returnJson());
        assertTrue(c.returnJson().contains("boom"));
    }

    @Test
    void argsExitIsCapturedSeparately() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.f()V", "t", 1, 1),
                args(Map.of("v", 1)),
                me(OUT, "t", 2, 1),
                ReturnRecord.ofVoid(),
                argsExit(Map.of("v", 2)));

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertNotNull(c.argsJson());
        assertNotNull(c.argsExitJson());
        assertTrue(c.argsJson().contains("\"v\":1"));
        assertTrue(c.argsExitJson().contains("\"v\":2"));
    }

    @Test
    void thisAsFullCborGoesIntoThisJsonNotRefAndIsHashEnriched() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.f()V", "t", 1, 1),
                new ThisInstanceRecord(Codec.encode(Map.of("field", "value"))),
                me(OUT, "t", 2, 1),
                ReturnRecord.ofVoid());

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertNull(c.thisIdRef());
        assertNotNull(c.thisJson());
        assertTrue(c.thisJson().contains("__meta__"),
                "TI payload JSON must carry Merkle __meta__ enrichment");
    }

    @Test
    void staticMethodHasNeitherThisRefNorJson() {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.staticThing()V", "t", 1, 1),
                me(OUT, "t", 2, 1),
                ReturnRecord.ofVoid());

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertNull(c.thisIdRef());
        assertNull(c.thisJson());
    }

    @Test
    void unmatchedMeWithoutOpenCallIsIgnored() {
        // ME with a callId that was never seen as MS — orphan, drop silently.
        // Defence-in-depth: RequestRecorder's failed-entry contract should
        // suppress the matching exit upstream, but the parser must still not
        // produce a half-built call if a stray ME ever slips through.
        List<TraceRecord> records = List.of(
                me(OUT, "t", 1, 1),
                ReturnRecord.ofVoid());
        assertEquals(0, new RecordParser().parse(records).size());
    }

    @Test
    void unmatchedMsWithoutMeStaysOpenAcrossBatches() {
        // Truncated stream — agent crashed mid-call. The MS lives in the
        // open-calls map until the TTL sweep reaps it (see
        // staleOpenCallIsEvictedAfterTtl); it never surfaces as a completed
        // call.
        RecordParser parser = new RecordParser();
        assertEquals(0, parser.parse(List.of(
                ms(OUT, null, "F.f()V", "t", 1, 1))).size());
        assertEquals(1, parser.openCallCount());
    }

    @Test
    void agentOrderMeBeforeReturnAttachesReturnToCorrectCall() throws IOException {
        // Real wire order from RequestRecorder.recordExit():
        //   METHOD_END, RETURN, ARGUMENTS_EXIT
        // i.e. the ME comes BEFORE the call's own return/args-exit records.
        // The parser's exit context holds across them until the next MS/ME.
        List<TraceRecord> records = List.of(
                ms(OUT, null, "F.f()I", "t", 1000, 1),
                args(Map.of()),
                me(OUT, "t", 2000, 1),
                new ReturnRecord(Codec.encode(42)),
                argsExit(Map.of()));

        List<ParsedCall> calls = new RecordParser().parse(records);
        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals(2000L, c.tsOutMillis());
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
        assertNotNull(c.argsExitJson());
    }

    @Test
    void agentOrderNestedDoesNotLeakReturnToParent() throws IOException {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A.outer()V", "t", 1000, 1),
                ms(INN, OUT, "B.inner()I", "t", 1100, 1),
                me(INN, "t", 1200, 1),
                new ReturnRecord(Codec.encode(7)),
                me(OUT, "t", 1300, 1),
                ReturnRecord.ofVoid());

        List<ParsedCall> calls = new RecordParser().parse(records);
        assertEquals(2, calls.size());
        ParsedCall inner = calls.get(0);
        assertEquals("B.inner()I", inner.signature());
        assertEquals("VALUE", inner.returnType());
        assertEquals("7", inner.returnJson());
        ParsedCall outer = calls.get(1);
        assertEquals("A.outer()V", outer.signature());
        assertEquals("VOID", outer.returnType());
        assertNull(outer.returnJson());
    }

    @Test
    void versionBannerIsIgnored() {
        List<TraceRecord> records = List.of(
                VersionRecord.current(),
                ms(OUT, null, "F.f()V", "t", 1, 1),
                me(OUT, "t", 2, 1),
                ReturnRecord.ofVoid());
        assertEquals(1, new RecordParser().parse(records).size());
    }

    // ============================================================
    //  Cross-batch pairing — the central reason the parser is
    //  stateful and UUID-keyed: a call whose MS lands in batch N
    //  and ME in batch N+1 must still pair.
    // ============================================================

    @Test
    void msInOneBatchAndMeInAnotherStillPair() {
        RecordParser parser = new RecordParser();

        assertEquals(0, parser.parse(List.of(
                        ms(OUT, null, "F.f()V", "t", 1000, 1))).size(),
                "MS without ME yields no completed call yet");

        List<ParsedCall> completed = parser.parse(List.of(
                me(OUT, "t", 2000, 1),
                ReturnRecord.ofVoid()));

        assertEquals(1, completed.size(), "MS↔ME must pair across batches");
        ParsedCall c = completed.get(0);
        assertEquals(OUT, c.callId());
        assertEquals(1000L, c.tsInMillis());
        assertEquals(2000L, c.tsOutMillis());
    }

    @Test
    void interleavedThreadsInOneBatchPairCorrectlyByCallId() {
        // Two concurrent calls on different threads, records interleaved
        // (which is what happens in production — the global RecordBuffer
        // is drained in time order, mixing threads). UUID-keyed pairing
        // handles what a stack-based parser would mispair.
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A.a()V", "t1", 1000, 1),
                ms(INN, null, "B.b()V", "t2", 1010, 2),
                me(INN, "t2", 1020, 2),
                ReturnRecord.ofVoid(),
                me(OUT, "t1", 1030, 1),
                ReturnRecord.ofVoid());

        List<ParsedCall> calls = new RecordParser().parse(records);

        assertEquals(2, calls.size());
        // First completed: t2's call (its ME came first).
        ParsedCall first = calls.get(0);
        assertEquals(INN, first.callId());
        assertEquals("t2", first.threadName());
        assertEquals(1010L, first.tsInMillis());
        assertEquals(1020L, first.tsOutMillis());
        // Second completed: t1's call.
        ParsedCall second = calls.get(1);
        assertEquals(OUT, second.callId());
        assertEquals("t1", second.threadName());
        assertEquals(1000L, second.tsInMillis());
        assertEquals(1030L, second.tsOutMillis());
    }

    @Test
    void staleOpenCallIsEvictedAfterTtl() {
        // L-01: an MS without a matching ME (agent crash mid-call) used to
        // sit in openCalls forever. The TTL sweep at the end of parse() must
        // reap it once admission age exceeds the 10-minute TTL. Sweep is
        // throttled to once per minute, so we advance the clock past both.
        long[] now = { 0L };
        RecordParser parser = new RecordParser(() -> now[0]);

        now[0] = 0L;
        parser.parse(List.of(ms(OUT, null, "F.f()V", "t", 0, 1)));
        assertEquals(1, parser.openCallCount(),
                "MS without ME should leave one builder in openCalls");

        // Inside TTL — sweep runs but evicts nothing.
        now[0] = 5 * 60 * 1000L;
        parser.parse(List.of());
        assertEquals(1, parser.openCallCount(),
                "entry under TTL must not be evicted");

        // Past TTL, past sweep interval — eviction fires.
        now[0] = 11 * 60 * 1000L;
        parser.parse(List.of());
        assertEquals(0, parser.openCallCount(),
                "entry older than TTL must be evicted by the sweep");

        // A late ME for the evicted call is now an orphan, dropped silently.
        List<ParsedCall> late = parser.parse(List.of(
                me(OUT, "t", 12000, 1),
                ReturnRecord.ofVoid()));
        assertEquals(0, late.size(),
                "ME for a previously-evicted call must be treated as orphan");
    }

    @Test
    void sequenceRecordAttachesSeqByCallId() {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A()V", "t", 1000, 1),
                new SequenceRecord(OUT, 7),
                me(OUT, "t", 1100, 1),
                ReturnRecord.ofVoid());

        ParsedCall c = new RecordParser().parse(records).get(0);
        assertEquals(7L, c.seq());
    }

    @Test
    void sequenceRecordRoutesByCallIdNotAdjacency() {
        // Two MS records with their SQ records arriving in the "wrong" order
        // vs their MSs — routing is by callId, not stream adjacency.
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A()V", "t", 1000, 1),
                ms(INN, OUT, "B()V", "t", 1100, 1),
                new SequenceRecord(INN, 11),
                new SequenceRecord(OUT, 10),
                me(INN, "t", 1200, 1),
                ReturnRecord.ofVoid(),
                me(OUT, "t", 1300, 1),
                ReturnRecord.ofVoid());

        List<ParsedCall> calls = new RecordParser().parse(records);
        // Order: inner emitted first (ME for INN) then outer.
        assertEquals(11L, calls.get(0).seq(), "B's seq must be 11");
        assertEquals(10L, calls.get(1).seq(), "A's seq must be 10");
    }

    @Test
    void sequenceRecordAbsentLeavesSeqAtZero() {
        List<TraceRecord> records = List.of(
                ms(OUT, null, "A()V", "t", 1000, 1),
                me(OUT, "t", 1100, 1),
                ReturnRecord.ofVoid());

        assertEquals(0L, new RecordParser().parse(records).get(0).seq());
    }

    // --- record builders -----------------------------------------------

    private static MethodStartRecord ms(UUID callId, UUID parentCallId, String signature,
                                        String threadName, long timestamp, long requestId) {
        return new MethodStartRecord("sess-1", signature, threadName, timestamp, 1,
                requestId, callId, parentCallId);
    }

    private static MethodEndRecord me(UUID callId, String threadName, long timestamp, long requestId) {
        return new MethodEndRecord("sess-1", threadName, timestamp, requestId, callId);
    }

    private static ArgumentsRecord args(Map<String, Object> argsMap) throws IOException {
        return new ArgumentsRecord(Codec.encode(argsMap));
    }

    private static ArgumentsExitRecord argsExit(Map<String, Object> argsMap) throws IOException {
        return new ArgumentsExitRecord(Codec.encode(argsMap));
    }
}
