package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.recorder.record.ArgumentsExitRecord;
import com.github.gabert.deepflow.recorder.record.ArgumentsRecord;
import com.github.gabert.deepflow.recorder.record.ExceptionRecord;
import com.github.gabert.deepflow.recorder.record.MethodEndRecord;
import com.github.gabert.deepflow.recorder.record.MethodStartRecord;
import com.github.gabert.deepflow.recorder.record.RecordType;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import com.github.gabert.deepflow.recorder.record.ReturnRecord;
import com.github.gabert.deepflow.recorder.record.ThisInstanceRecord;
import com.github.gabert.deepflow.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.deepflow.recorder.record.TraceRecord;
import com.github.gabert.deepflow.recorder.record.VersionRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the typed {@link TraceRecord} implementations produce byte-for-byte
 * identical frames to {@link RecordWriter} (the legacy facade) and parse them
 * back into equivalent records. This is the safety net for the upcoming
 * cutover that replaces RecordWriter call sites with direct record
 * construction.
 */
class TypedRecordTest {

    // ============================================================
    //  Typed-record output matches RecordWriter byte-for-byte
    // ============================================================

    @Test
    void versionRecord_matchesRecordWriterFrame() {
        byte[] typed = new VersionRecord((short) 1, (short) 2).toFrame();
        byte[] legacy = RecordWriter.version((short) 1, (short) 2);
        assertArrayEquals(legacy, typed);
    }

    @Test
    void methodStartRecord_matchesRecordWriterFrame() {
        byte[] typed = new MethodStartRecord(
                "S", "M", "T",
                0x0102030405060708L, 0x0A0B0C0D, 0x1112131415161718L
        ).toFrame();
        byte[] legacy = RecordWriter.logEntrySimple(
                "S", "M", "T",
                0x0102030405060708L, 0x0A0B0C0D, 0x1112131415161718L);
        assertArrayEquals(legacy, typed);
    }

    @Test
    void methodStartRecord_nullSessionIdMatchesLegacy() {
        byte[] typed = new MethodStartRecord(null, "M", "T", 0L, 0, 0L).toFrame();
        byte[] legacy = RecordWriter.logEntrySimple(null, "M", "T", 0L, 0, 0L);
        assertArrayEquals(legacy, typed);
    }

    @Test
    void methodEndRecord_matchesRecordWriterFrame() {
        byte[] typed = new MethodEndRecord(
                "S", "T", 0x0102030405060708L, 0x1112131415161718L
        ).toFrame();
        byte[] legacy = RecordWriter.methodEnd("S", "T", 0x0102030405060708L, 0x1112131415161718L);
        assertArrayEquals(legacy, typed);
    }

    @Test
    void methodEndRecord_nullSessionIdMatchesLegacy() {
        byte[] typed = new MethodEndRecord(null, "T", 0L, 0L).toFrame();
        byte[] legacy = RecordWriter.methodEnd(null, "T", 0L, 0L);
        assertArrayEquals(legacy, typed);
    }

    @Test
    void argumentsRecord_matchesRecordWriterFrame() {
        byte[] cbor = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        assertArrayEquals(RecordWriter.arguments(cbor), new ArgumentsRecord(cbor).toFrame());
    }

    @Test
    void argumentsExitRecord_matchesRecordWriterFrame() {
        byte[] cbor = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        assertArrayEquals(RecordWriter.argumentsExit(cbor), new ArgumentsExitRecord(cbor).toFrame());
    }

    @Test
    void thisInstanceRecord_matchesRecordWriterFrame() {
        byte[] cbor = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        assertArrayEquals(RecordWriter.thisInstance(cbor), new ThisInstanceRecord(cbor).toFrame());
    }

    @Test
    void thisInstanceRefRecord_matchesRecordWriterFrame() {
        long objectId = 0x123456789ABCDEF0L;
        assertArrayEquals(RecordWriter.thisInstanceRef(objectId),
                new ThisInstanceRefRecord(objectId).toFrame());
    }

    @Test
    void returnValueRecord_matchesRecordWriterFrame() {
        byte[] cbor = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        assertArrayEquals(RecordWriter.returnValue(cbor), new ReturnRecord(cbor).toFrame());
    }

    @Test
    void returnVoidRecord_matchesRecordWriterFrame() {
        assertArrayEquals(RecordWriter.returnVoid(), ReturnRecord.ofVoid().toFrame());
    }

    @Test
    void exceptionRecord_matchesRecordWriterFrame() {
        byte[] cbor = {(byte) 0xBA, (byte) 0xDB, (byte) 0xAD, (byte) 0xBA};
        assertArrayEquals(RecordWriter.exception(cbor), new ExceptionRecord(cbor).toFrame());
    }

    // ============================================================
    //  Round-trip via TraceRecord.parse(typeByte, payload)
    // ============================================================

    @Test
    void versionRecord_roundTrips() {
        VersionRecord original = new VersionRecord((short) 3, (short) 7);
        TraceRecord parsed = TraceRecord.parse(VersionRecord.TYPE, original.payloadBytes());
        VersionRecord r = assertInstanceOf(VersionRecord.class, parsed);
        assertEquals((short) 3, r.major());
        assertEquals((short) 7, r.minor());
    }

    @Test
    void methodStartRecord_roundTrips() {
        MethodStartRecord original = new MethodStartRecord(
                "session-1", "Foo.bar()", "main", 1234567890L, 42, 99L);
        TraceRecord parsed = TraceRecord.parse(MethodStartRecord.TYPE, original.payloadBytes());
        MethodStartRecord r = assertInstanceOf(MethodStartRecord.class, parsed);
        assertEquals("session-1", r.sessionId());
        assertEquals("Foo.bar()", r.signature());
        assertEquals("main", r.threadName());
        assertEquals(1234567890L, r.timestamp());
        assertEquals(42, r.callerLine());
        assertEquals(99L, r.requestId());
    }

    @Test
    void methodStartRecord_nullSessionIdRoundTripsAsNull() {
        MethodStartRecord original = new MethodStartRecord(null, "M", "T", 0L, 0, 0L);
        TraceRecord parsed = TraceRecord.parse(MethodStartRecord.TYPE, original.payloadBytes());
        assertNull(((MethodStartRecord) parsed).sessionId());
    }

    @Test
    void methodEndRecord_roundTrips() {
        MethodEndRecord original = new MethodEndRecord("S", "T", 999L, 7L);
        TraceRecord parsed = TraceRecord.parse(MethodEndRecord.TYPE, original.payloadBytes());
        MethodEndRecord r = assertInstanceOf(MethodEndRecord.class, parsed);
        assertEquals("S", r.sessionId());
        assertEquals("T", r.threadName());
        assertEquals(999L, r.timestamp());
        assertEquals(7L, r.requestId());
    }

    @Test
    void thisInstanceRefRecord_roundTrips() {
        ThisInstanceRefRecord original = new ThisInstanceRefRecord(0x123456789ABCDEF0L);
        TraceRecord parsed = TraceRecord.parse(ThisInstanceRefRecord.TYPE, original.payloadBytes());
        ThisInstanceRefRecord r = assertInstanceOf(ThisInstanceRefRecord.class, parsed);
        assertEquals(0x123456789ABCDEF0L, r.objectId());
    }

    @Test
    void cborWrappingRecords_roundTripPayloadAsIs() {
        byte[] cbor = {0x01, 0x02, 0x03, 0x04};

        // ArgumentsRecord
        TraceRecord ar = TraceRecord.parse(ArgumentsRecord.TYPE, cbor);
        assertInstanceOf(ArgumentsRecord.class, ar);
        assertArrayEquals(cbor, ar.payloadBytes());

        // ArgumentsExitRecord
        TraceRecord ax = TraceRecord.parse(ArgumentsExitRecord.TYPE, cbor);
        assertInstanceOf(ArgumentsExitRecord.class, ax);
        assertArrayEquals(cbor, ax.payloadBytes());

        // ThisInstanceRecord
        TraceRecord ti = TraceRecord.parse(ThisInstanceRecord.TYPE, cbor);
        assertInstanceOf(ThisInstanceRecord.class, ti);
        assertArrayEquals(cbor, ti.payloadBytes());

        // ExceptionRecord
        TraceRecord ex = TraceRecord.parse(ExceptionRecord.TYPE, cbor);
        assertInstanceOf(ExceptionRecord.class, ex);
        assertArrayEquals(cbor, ex.payloadBytes());

        // ReturnRecord (value)
        TraceRecord rv = TraceRecord.parse(ReturnRecord.TYPE, cbor);
        ReturnRecord rvCast = assertInstanceOf(ReturnRecord.class, rv);
        assertFalse(rvCast.isVoid());
        assertArrayEquals(cbor, rv.payloadBytes());
    }

    @Test
    void returnRecord_voidParsesFromZeroLengthPayload() {
        TraceRecord rv = TraceRecord.parse(ReturnRecord.TYPE, new byte[0]);
        ReturnRecord r = assertInstanceOf(ReturnRecord.class, rv);
        assertTrue(r.isVoid());
        assertEquals(0, r.payloadBytes().length);
    }

    // ============================================================
    //  Dispatcher safety
    // ============================================================

    @Test
    void parse_unknownTypeByteThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceRecord.parse((byte) 0x7F, new byte[0]));
    }

    @Test
    void everyKnownTypeByteHasADispatchCase() {
        // If a new RecordType is added but TraceRecord.parse forgets it,
        // this test fails — catches the spread across files.
        byte[] ALL = {
                RecordType.VERSION,
                RecordType.METHOD_START,
                RecordType.METHOD_END,
                RecordType.ARGUMENTS,
                RecordType.ARGUMENTS_EXIT,
                RecordType.RETURN,
                RecordType.EXCEPTION,
                RecordType.THIS_INSTANCE,
                RecordType.THIS_INSTANCE_REF
        };
        for (byte b : ALL) {
            byte[] payload = (b == RecordType.METHOD_START)
                    ? new MethodStartRecord(null, "", "", 0L, 0, 0L).payloadBytes()
                    : (b == RecordType.METHOD_END)
                    ? new MethodEndRecord(null, "", 0L, 0L).payloadBytes()
                    : (b == RecordType.THIS_INSTANCE_REF)
                    ? new ThisInstanceRefRecord(0L).payloadBytes()
                    : (b == RecordType.VERSION)
                    ? new VersionRecord((short) 0, (short) 0).payloadBytes()
                    : new byte[0];
            TraceRecord r = TraceRecord.parse(b, payload);
            assertNotNull(r, "type byte 0x" + String.format("%02X", b) + " must have a dispatch case");
            assertEquals(b, r.typeByte());
        }
    }
}
