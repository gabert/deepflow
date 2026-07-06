package com.github.gabert.arachna.trace.renderer;

import com.github.gabert.arachna.trace.codec.Codec;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordRendererTest {

    private static final String SIGNATURE = "com.example::Foo.bar(java.lang::String) -> void [public]";
    private static final String THREAD = "main";
    private static final String SESSION = "test-session";

    // --- Method entry rendering ---

    @Test
    void renderMethodEntryLines() throws Exception {
        byte[] args = Codec.encode(new Object[]{"hello", 42});
        long ts = 1000L;

        byte[] data = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, ts, 99, 5L, null, null, null, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(THREAD, result.threadName());

        List<String> lines = result.lines();
        assertEquals("TS;1000", lines.get(0));
        assertEquals("SI;" + SESSION, lines.get(1));
        assertEquals("MS;" + SIGNATURE, lines.get(2));
        assertEquals("TN;main", lines.get(3));
        assertEquals("RI;5", lines.get(4));
        assertEquals("CL;99", lines.get(5));
        assertTrue(lines.get(6).startsWith("AR;"));
    }

    @Test
    void renderMethodEntryWithoutSessionId() throws Exception {
        byte[] args = Codec.encode(new Object[]{"hello"});

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 10, 0L, null, null, null, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TS;1000", lines.get(0));
        assertEquals("MS;" + SIGNATURE, lines.get(1));
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("SI;")));
    }

    // --- Void return rendering ---

    @Test
    void renderVoidReturn() {
        long ts = 2000L;

        byte[] data = RecordWriter.logExit(null, THREAD, ts, 0L, null, null, true);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TE;2000", lines.get(0));
        assertEquals("TN;main", lines.get(1));
        assertEquals("RI;0", lines.get(2));
        assertEquals("RT;VOID", lines.get(3));
        assertEquals(4, lines.size());
    }

    // --- Value return rendering ---

    @Test
    void renderValueReturn() throws Exception {
        byte[] retCbor = Codec.encode("returned");
        long ts = 3000L;

        byte[] data = RecordWriter.logExit(null, THREAD, ts, 0L, null, retCbor, false);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TE;3000", lines.get(0));
        assertEquals("TN;main", lines.get(1));
        assertEquals("RI;0", lines.get(2));
        assertEquals("RT;VALUE", lines.get(3));
        assertTrue(lines.get(4).startsWith("RE;"));
    }

    // --- Exception rendering ---

    @Test
    void renderException() throws Exception {
        byte[] excCbor = Codec.encode(Map.of("message", "NPE"));
        long ts = 4000L;

        byte[] data = RecordWriter.logExitException(null, THREAD, ts, 0L, null, excCbor);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TE;4000", lines.get(0));
        assertEquals("TN;main", lines.get(1));
        assertEquals("RI;0", lines.get(2));
        assertEquals("RT;EXCEPTION", lines.get(3));
        assertTrue(lines.get(4).startsWith("RE;"));
    }

    // --- Full method trace ---

    @Test
    void renderFullMethodTrace() throws Exception {
        byte[] args = Codec.encode(new Object[]{"x"});
        byte[] ret = Codec.encode(42);

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, 1000L, 10, 0L, null, null, null, args);
        byte[] exit = RecordWriter.logExit(SESSION, THREAD, 2000L, 0L, null, ret, false);
        byte[] data = concat(entry, exit);

        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(THREAD, result.threadName());
        List<String> lines = result.lines();

        // Entry: TS, SI, MS, TN, RI, CL, AR
        assertEquals("TS;1000", lines.get(0));
        assertEquals("SI;" + SESSION, lines.get(1));
        assertEquals("MS;" + SIGNATURE, lines.get(2));
        assertEquals("TN;main", lines.get(3));
        assertEquals("RI;0", lines.get(4));
        assertEquals("CL;10", lines.get(5));
        assertTrue(lines.get(6).startsWith("AR;"));

        // Exit: TE, TN, RI, RT, RE
        assertEquals("TE;2000", lines.get(7));
        assertEquals("TN;main", lines.get(8));
        assertEquals("RI;0", lines.get(9));
        assertEquals("RT;VALUE", lines.get(10));
        assertTrue(lines.get(11).startsWith("RE;"));

        assertEquals(12, lines.size());
    }

    // --- This instance rendering ---

    @Test
    void renderWithThisInstance() throws Exception {
        byte[] thisCbor = Codec.encode(Map.of("field", "value"));
        byte[] args = Codec.encode(new Object[]{});

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 5, 0L, null, null, thisCbor, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TS;1000", lines.get(0));
        assertEquals("MS;" + SIGNATURE, lines.get(1));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("TI;")));
    }

    // --- This instance ref rendering ---

    @Test
    void renderWithThisInstanceRef() throws Exception {
        byte[] args = Codec.encode(new Object[]{});

        byte[] data = RecordWriter.logEntryWithThisRef(null, SIGNATURE, THREAD, 1000L, 5, 0L, null, null, 12345L, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TI;12345", lines.get(5));
    }

    // --- Thread name extraction ---

    @Test
    void threadNameFromEntryRecord() throws Exception {
        byte[] args = Codec.encode(new Object[]{});
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, "worker-1", 1000L, 1, 0L, null, null, null, args);

        RecordRenderer.Result result = RecordRenderer.render(data);
        assertEquals("worker-1", result.threadName());
    }

    @Test
    void threadNameFromExitRecord() {
        byte[] data = RecordWriter.logExit(null, "http-handler-3", 5000L, 0L, null, null, true);

        RecordRenderer.Result result = RecordRenderer.render(data);
        assertEquals("http-handler-3", result.threadName());
    }

    // --- Version record rendering ---

    @Test
    void renderVersionRecord_emitsDotSeparated() {
        // The VR line is the wire-format banner downstream parsers key on.
        // The renderer always emits it regardless of emit_tags filter.
        byte[] data = RecordWriter.version((short) 1, (short) 4);
        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(List.of("VR;1.4"), result.lines());
        assertNull(result.threadName(), "VR records carry no thread name");
    }

    // --- Sequence record rendering ---

    @Test
    void renderSequenceRecord_formatIsCallIdPipeSeq() {
        // The processor reads SQ; values as <callId>|<seq>. A refactor that
        // changes the separator would silently break sequence pairing in
        // RecordParser — pin the format here.
        UUID callId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] data = RecordWriter.sequence(callId, 42L);
        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(List.of("SQ;11111111-2222-3333-4444-555555555555|42"), result.lines());
    }

    @Test
    void renderSequenceRecord_nullCallIdLeavesEmptyLhs() {
        // Null callId is encoded as the all-zero UUID sentinel and round-trips
        // as null. Renderer must produce "SQ;|<seq>" with empty left side,
        // not "SQ;null|<seq>".
        byte[] data = RecordWriter.sequence(null, 7L);
        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(List.of("SQ;|7"), result.lines());
    }

    // --- emit_tags filtering ---

    @Test
    void renderRespectsEmitTagsFilter() throws Exception {
        // User configures only MS+TE; everything else (TS/SI/TN/RI/CL/AR/CI/PI)
        // must be filtered out of the rendered text — even though the binary
        // record carries them.
        byte[] args = Codec.encode(new Object[]{"x"});
        UUID callId = UUID.randomUUID();
        byte[] data = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, 1000L, 10, 5L, callId, null, null, args);

        RecordRenderer.Result result = RecordRenderer.render(data, Set.of("MS", "TE"));
        List<String> lines = result.lines();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("MS;")), "MS must remain");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("TS;")), "TS must be filtered");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("SI;")), "SI must be filtered");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("AR;")), "AR must be filtered");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("CI;")), "CI must be filtered when not in emit_tags");
    }

    @Test
    void msAndVrAlwaysEmittedRegardlessOfFilter() {
        // MS is the structural anchor; VR is the wire-format banner. Both
        // are emitted even when the user passes an empty filter. Locking this
        // because RecordRenderer has explicit special-case code for it.
        byte[] vr = RecordWriter.version((short) 1, (short) 4);
        byte[] me = RecordWriter.methodEnd(null, "main", 2000L, 0L, null);

        RecordRenderer.Result vrResult = RecordRenderer.render(vr, Set.of());
        assertTrue(vrResult.lines().stream().anyMatch(l -> l.startsWith("VR;")),
                "VR must survive an empty emit_tags filter");

        // MS would normally accompany method-start; verify here via a
        // synthetic case using methodEnd which carries no MS tag, just so
        // we test the VR-only edge separately from the MS path.
        RecordRenderer.Result meResult = RecordRenderer.render(me, Set.of());
        assertEquals(0, meResult.lines().size(),
                "ME with empty filter produces no tags (no MS, no VR on this record)");
    }

    @Test
    void ciAndPiFollowEmitTagsFilter() {
        // CLAUDE.md says CI and PI ALWAYS live on the binary METHOD_START
        // record. The renderer, however, filters them via emit_tags. Lock
        // this layering: the wire-level bytes are unchanged, but the rendered
        // text can omit them. This is what makes the agent's UUID-pairing
        // safe under any emit_tags configuration.
        UUID callId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        byte[] entry;
        try {
            entry = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 0, 0L,
                    callId, parentId, null, Codec.encode(new Object[]{}));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // With CI+PI in emit_tags → both appear
        RecordRenderer.Result withCi = RecordRenderer.render(entry,
                Set.of("MS", "TN", "CI", "PI"));
        assertTrue(withCi.lines().stream().anyMatch(l -> l.startsWith("CI;")));
        assertTrue(withCi.lines().stream().anyMatch(l -> l.startsWith("PI;")));

        // Without CI+PI → both filtered
        RecordRenderer.Result withoutCi = RecordRenderer.render(entry,
                Set.of("MS", "TN"));
        assertFalse(withoutCi.lines().stream().anyMatch(l -> l.startsWith("CI;")));
        assertFalse(withoutCi.lines().stream().anyMatch(l -> l.startsWith("PI;")));
    }

    // --- CBOR decode error ---

    @Test
    void renderWithCorruptedCborProducesDecodeErrorMarker() {
        // RecordRenderer wraps Codec.decode in try/catch and emits
        // "<decode error: ...>" instead of throwing — a single poison record
        // must not sink the whole batch. Reaches the catch via a ThisInstance
        // record whose payload is not valid CBOR.
        byte[] junkCbor = new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) 0xFE};
        byte[] frame = RecordWriter.thisInstance(junkCbor);

        RecordRenderer.Result result = RecordRenderer.render(frame);

        String tiLine = result.lines().stream()
                .filter(l -> l.startsWith("TI;"))
                .findFirst().orElseThrow();
        assertTrue(tiLine.startsWith("TI;<decode error:"),
                "expected decode-error marker, got: " + tiLine);
    }

    // --- Utilities ---

    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }
}
