package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

        byte[] data = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, ts, 99, 2, 5L, 3L, null, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(THREAD, result.threadName());

        List<String> lines = result.lines();
        assertEquals("SI;" + SESSION, lines.get(0));
        assertEquals("MS;" + SIGNATURE, lines.get(1));
        assertEquals("TN;main", lines.get(2));
        assertEquals("CI;5", lines.get(3));
        assertEquals("PI;3", lines.get(4));
        assertEquals("TS;1000", lines.get(5));
        assertEquals("CL;99", lines.get(6));
        assertTrue(lines.get(7).startsWith("AR;"));
    }

    @Test
    void renderMethodEntryWithoutSessionId() throws Exception {
        byte[] args = Codec.encode(new Object[]{"hello"});

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 10, 0, 0L, -1L, null, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("MS;" + SIGNATURE, lines.get(0));
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("SI;")));
    }

    // --- Void return rendering ---

    @Test
    void renderVoidReturn() {
        long ts = 2000L;

        byte[] data = RecordWriter.logExit(null, THREAD, ts, null, true);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("RT;VOID", lines.get(0));
        assertEquals("TN;main", lines.get(1));
        assertEquals("TE;2000", lines.get(2));
        assertEquals(3, lines.size());
    }

    // --- Value return rendering ---

    @Test
    void renderValueReturn() throws Exception {
        byte[] retCbor = Codec.encode("returned");
        long ts = 3000L;

        byte[] data = RecordWriter.logExit(null, THREAD, ts, retCbor, false);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("RT;VALUE", lines.get(0));
        assertTrue(lines.get(1).startsWith("RE;"));
        assertEquals("TN;main", lines.get(2));
        assertEquals("TE;3000", lines.get(3));
    }

    // --- Exception rendering ---

    @Test
    void renderException() throws Exception {
        byte[] excCbor = Codec.encode(Map.of("message", "NPE"));
        long ts = 4000L;

        byte[] data = RecordWriter.logExitException(null, THREAD, ts, excCbor);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("RT;EXCEPTION", lines.get(0));
        assertTrue(lines.get(1).startsWith("RE;"));
        assertEquals("TN;main", lines.get(2));
        assertEquals("TE;4000", lines.get(3));
    }

    // --- Full method trace ---

    @Test
    void renderFullMethodTrace() throws Exception {
        byte[] args = Codec.encode(new Object[]{"x"});
        byte[] ret = Codec.encode(42);

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, 1000L, 10, 0, 0L, -1L, null, args);
        byte[] exit = RecordWriter.logExit(SESSION, THREAD, 2000L, ret, false);
        byte[] data = concat(entry, exit);

        RecordRenderer.Result result = RecordRenderer.render(data);

        assertEquals(THREAD, result.threadName());
        List<String> lines = result.lines();

        // Entry: SI, MS, TN, CI, PI, TS, CL, AR
        assertEquals("SI;" + SESSION, lines.get(0));
        assertEquals("MS;" + SIGNATURE, lines.get(1));
        assertEquals("TN;main", lines.get(2));
        assertEquals("CI;0", lines.get(3));
        assertEquals("PI;-1", lines.get(4));
        assertEquals("TS;1000", lines.get(5));
        assertEquals("CL;10", lines.get(6));
        assertTrue(lines.get(7).startsWith("AR;"));

        // Exit: RT, RE, TN, TE
        assertEquals("RT;VALUE", lines.get(8));
        assertTrue(lines.get(9).startsWith("RE;"));
        assertEquals("TN;main", lines.get(10));
        assertEquals("TE;2000", lines.get(11));

        assertEquals(12, lines.size());
    }

    // --- This instance rendering ---

    @Test
    void renderWithThisInstance() throws Exception {
        byte[] thisCbor = Codec.encode(Map.of("field", "value"));
        byte[] args = Codec.encode(new Object[]{});

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 5, 0, 0L, -1L, thisCbor, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("MS;" + SIGNATURE, lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("TI;")));
    }

    // --- This instance ref rendering ---

    @Test
    void renderWithThisInstanceRef() throws Exception {
        byte[] args = Codec.encode(new Object[]{});

        byte[] data = RecordWriter.logEntryWithThisRef(null, SIGNATURE, THREAD, 1000L, 5, 0, 0L, -1L, 12345L, args);
        RecordRenderer.Result result = RecordRenderer.render(data);

        List<String> lines = result.lines();
        assertEquals("TI;12345", lines.get(6));
    }

    // --- Thread name extraction ---

    @Test
    void threadNameFromEntryRecord() throws Exception {
        byte[] args = Codec.encode(new Object[]{});
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, "worker-1", 1000L, 1, 0, 0L, -1L, null, args);

        RecordRenderer.Result result = RecordRenderer.render(data);
        assertEquals("worker-1", result.threadName());
    }

    @Test
    void threadNameFromExitRecord() {
        byte[] data = RecordWriter.logExit(null, "http-handler-3", 5000L, null, true);

        RecordRenderer.Result result = RecordRenderer.render(data);
        assertEquals("http-handler-3", result.threadName());
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
