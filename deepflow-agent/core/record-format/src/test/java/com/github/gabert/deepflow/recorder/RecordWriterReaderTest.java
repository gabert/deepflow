package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.*;
import com.github.gabert.deepflow.recorder.record.TraceRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordWriterReaderTest {

    private static final String SIGNATURE = "com.example::Foo.bar(java.lang::String) -> void [public]";
    private static final String THREAD = "main";
    private static final String SESSION = "test-session-01";

    // --- logEntry ---

    @Test
    void logEntryProducesTwoRecords() throws Exception {
        byte[] argsCbor = Codec.encode(new Object[]{"arg1", 42});
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, ts, 99, 0L, null, argsCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        assertEquals(RecordType.METHOD_START, records.get(0).type());
        assertEquals(RecordType.ARGUMENTS, records.get(1).type());

        MethodStartData meta = RecordReader.decodeMethodStart(records.get(0));
        assertEquals(SESSION, meta.sessionId);
        assertEquals(SIGNATURE, meta.signature);
        assertEquals(THREAD, meta.threadName);
        assertEquals(ts, meta.timestamp);
        assertEquals(99, meta.callerLine);

        assertArrayEquals(argsCbor, records.get(1).payload());
    }

    @Test
    void logEntryWithNullSessionId() throws Exception {
        byte[] argsCbor = Codec.encode(new Object[]{"arg1"});
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, ts, 10, 0L, null, argsCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        MethodStartData meta = RecordReader.decodeMethodStart(records.get(0));
        assertNull(meta.sessionId);
        assertEquals(SIGNATURE, meta.signature);
    }

    // --- logExit (value) ---

    @Test
    void logExitValueProducesTwoRecords() throws Exception {
        byte[] retCbor = Codec.encode("returned");
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(SESSION, THREAD, ts, retCbor, false);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        assertEquals(RecordType.METHOD_END, records.get(0).type());
        assertEquals(RecordType.RETURN, records.get(1).type());

        assertArrayEquals(retCbor, records.get(1).payload());

        MethodEndData meta = RecordReader.decodeMethodEnd(records.get(0));
        assertEquals(SESSION, meta.sessionId);
        assertEquals(ts, meta.timestamp);
        assertEquals(THREAD, meta.threadName);
    }

    // --- logExit (void) ---

    @Test
    void logExitVoidHasEmptyReturnPayload() {
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(SESSION, THREAD, ts, null, true);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        assertEquals(RecordType.METHOD_END, records.get(0).type());
        assertEquals(RecordType.RETURN, records.get(1).type());
        assertEquals(0, records.get(1).payload().length);

        MethodEndData meta = RecordReader.decodeMethodEnd(records.get(0));
        assertEquals(SESSION, meta.sessionId);
    }

    @Test
    void logExitWithNullSessionId() throws Exception {
        byte[] retCbor = Codec.encode("val");
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(null, THREAD, ts, retCbor, false);

        MethodEndData meta = RecordReader.decodeMethodEnd(RecordReader.readAll(data).get(0));
        assertNull(meta.sessionId);
        assertEquals(THREAD, meta.threadName);
    }

    // --- logExitException ---

    @Test
    void logExitExceptionProducesTwoRecords() throws Exception {
        byte[] excCbor = Codec.encode(Map.of("message", "NPE"));
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExitException(SESSION, THREAD, ts, excCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        assertEquals(RecordType.METHOD_END, records.get(0).type());
        assertEquals(RecordType.EXCEPTION, records.get(1).type());

        assertArrayEquals(excCbor, records.get(1).payload());

        MethodEndData meta = RecordReader.decodeMethodEnd(records.get(0));
        assertEquals(SESSION, meta.sessionId);
        assertEquals(ts, meta.timestamp);
        assertEquals(THREAD, meta.threadName);
    }

    // --- Full method trace ---

    @Test
    void fullMethodTraceRoundtrip() throws Exception {
        long tsStart = System.currentTimeMillis();
        long tsEnd = tsStart + 5;
        byte[] argsCbor = Codec.encode(new Object[]{"x", 1});
        byte[] retCbor = Codec.encode(42);

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, tsStart, 10, 0L, null, argsCbor);
        byte[] exit = RecordWriter.logExit(SESSION, THREAD, tsEnd, retCbor, false);

        byte[] stream = concat(entry, exit);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(4, records.size());
        assertEquals(RecordType.METHOD_START, records.get(0).type());
        assertEquals(RecordType.ARGUMENTS, records.get(1).type());
        assertEquals(RecordType.METHOD_END, records.get(2).type());
        assertEquals(RecordType.RETURN, records.get(3).type());

        MethodStartData startMeta = RecordReader.decodeMethodStart(records.get(0));
        MethodEndData endMeta = RecordReader.decodeMethodEnd(records.get(2));
        assertEquals(SESSION, startMeta.sessionId);
        assertEquals(SESSION, endMeta.sessionId);
        assertEquals(tsStart, startMeta.timestamp);
        assertEquals(tsEnd, endMeta.timestamp);
        assertEquals(THREAD, endMeta.threadName);
    }

    @Test
    void fullMethodTraceWithException() throws Exception {
        long tsStart = System.currentTimeMillis();
        long tsEnd = tsStart + 3;
        byte[] argsCbor = Codec.encode(new Object[]{"input"});
        byte[] excCbor = Codec.encode(Map.of("message", "fail", "stacktrace", List.of("at X.y(X.java:5)")));

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, tsStart, 20, 0L, null, argsCbor);
        byte[] exit = RecordWriter.logExitException(SESSION, THREAD, tsEnd, excCbor);

        byte[] stream = concat(entry, exit);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(4, records.size());
        assertEquals(RecordType.METHOD_START, records.get(0).type());
        assertEquals(RecordType.ARGUMENTS, records.get(1).type());
        assertEquals(RecordType.METHOD_END, records.get(2).type());
        assertEquals(RecordType.EXCEPTION, records.get(3).type());

        MethodStartData startMeta = RecordReader.decodeMethodStart(records.get(0));
        MethodEndData endMeta = RecordReader.decodeMethodEnd(records.get(2));
        assertEquals(SESSION, startMeta.sessionId);
        assertEquals(SESSION, endMeta.sessionId);
        assertEquals(tsEnd, endMeta.timestamp);
        assertEquals(THREAD, endMeta.threadName);
    }

    @Test
    void nestedMethodTraceRoundtrip() throws Exception {
        String sigOuter = "com.example::Service.handle() -> void [public]";
        String sigInner = "com.example::Dao.save(java.lang::String) -> int [public]";
        long ts1 = 1000L;
        long ts2 = 2000L;
        long ts3 = 3000L;
        long ts4 = 4000L;
        byte[] outerArgs = Codec.encode(new Object[]{});
        byte[] innerArgs = Codec.encode(new Object[]{"data"});
        byte[] innerRet = Codec.encode(1);

        byte[] entryOuter = RecordWriter.logEntry(SESSION, sigOuter, "http-handler-1", ts1, 10, 0L, null, outerArgs);
        byte[] entryInner = RecordWriter.logEntry(SESSION, sigInner, "http-handler-1", ts2, 20, 1L, null, innerArgs);
        byte[] exitInner = RecordWriter.logExit(SESSION, "http-handler-1", ts3, innerRet, false);
        byte[] exitOuter = RecordWriter.logExit(SESSION, "http-handler-1", ts4, null, true);

        byte[] stream = concat(entryOuter, entryInner, exitInner, exitOuter);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(8, records.size());

        // entry outer: METHOD_START + ARGUMENTS
        assertEquals(RecordType.METHOD_START, records.get(0).type());
        assertEquals(RecordType.ARGUMENTS, records.get(1).type());
        // entry inner: METHOD_START + ARGUMENTS
        assertEquals(RecordType.METHOD_START, records.get(2).type());
        assertEquals(RecordType.ARGUMENTS, records.get(3).type());
        // exit inner: METHOD_END + RETURN
        assertEquals(RecordType.METHOD_END, records.get(4).type());
        assertEquals(RecordType.RETURN, records.get(5).type());
        // exit outer: METHOD_END + RETURN (void)
        assertEquals(RecordType.METHOD_END, records.get(6).type());
        assertEquals(RecordType.RETURN, records.get(7).type());

        // Verify session ID on all start/end frames
        assertEquals(SESSION, RecordReader.decodeMethodStart(records.get(0)).sessionId);
        assertEquals(SESSION, RecordReader.decodeMethodStart(records.get(2)).sessionId);
        assertEquals(SESSION, RecordReader.decodeMethodEnd(records.get(4)).sessionId);
        assertEquals(SESSION, RecordReader.decodeMethodEnd(records.get(6)).sessionId);

        // Verify signatures and thread names
        MethodStartData outer = RecordReader.decodeMethodStart(records.get(0));
        MethodStartData inner = RecordReader.decodeMethodStart(records.get(2));
        assertEquals(sigOuter, outer.signature);
        assertEquals(sigInner, inner.signature);
        assertEquals("http-handler-1", outer.threadName);
        assertEquals("http-handler-1", inner.threadName);

        // Verify call IDs
        assertEquals(0L, outer.requestId);
        assertEquals(1L, inner.requestId);

        // Verify timestamps
        assertEquals(ts1, outer.timestamp);
        assertEquals(ts2, inner.timestamp);
        assertEquals(ts3, RecordReader.decodeMethodEnd(records.get(4)).timestamp);
        assertEquals(ts4, RecordReader.decodeMethodEnd(records.get(6)).timestamp);

        // Verify thread name in METHOD_END
        assertEquals("http-handler-1", RecordReader.decodeMethodEnd(records.get(4)).threadName);
        assertEquals("http-handler-1", RecordReader.decodeMethodEnd(records.get(6)).threadName);

        // Inner return has payload, outer return is void
        assertTrue(records.get(5).payload().length > 0);
        assertEquals(0, records.get(7).payload().length);
    }

    // --- RecordReader edge cases ---

    @Test
    void readAllFromInputStream() throws Exception {
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, Codec.encode(new Object[]{}));

        List<TraceRecord> records = RecordReader.readAll(new ByteArrayInputStream(data));
        assertEquals(2, records.size());
        assertEquals(RecordType.METHOD_START, records.get(0).type());
    }

    @Test
    void emptyInputReturnsEmptyList() {
        List<TraceRecord> records = RecordReader.readAll(new byte[0]);
        assertTrue(records.isEmpty());
    }

    @Test
    void truncatedFrameThrows() throws Exception {
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, Codec.encode(new Object[]{"x"}));
        byte[] chopped = Arrays.copyOf(data, data.length - 1);
        assertThrows(IllegalArgumentException.class, () -> RecordReader.readAll(chopped));
    }

    @Test
    void largePayload() throws Exception {
        byte[] bigArgs = new byte[100_000];
        Arrays.fill(bigArgs, (byte) 0x42);

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, bigArgs);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        assertArrayEquals(bigArgs, records.get(1).payload());
    }

    // --- Test utilities ---

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
