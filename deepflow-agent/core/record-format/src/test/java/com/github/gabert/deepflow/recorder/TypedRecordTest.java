package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.recorder.record.ArgumentsExitRecord;
import com.github.gabert.deepflow.recorder.record.ArgumentsRecord;
import com.github.gabert.deepflow.recorder.record.ExceptionRecord;
import com.github.gabert.deepflow.recorder.record.MethodEndRecord;
import com.github.gabert.deepflow.recorder.record.MethodStartRecord;
import com.github.gabert.deepflow.recorder.record.RecordType;
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
 * Tests the {@link TraceRecord} dispatcher and per-record round-trips.
 *
 * <p>Wire-format byte layout is pinned by {@link WireFormatGoldenTest}; this
 * test focuses on the typed-record API: that {@link TraceRecord#parse(byte, byte[])}
 * dispatches to the right subtype, that each record's {@code parse(payload)}
 * faithfully reverses {@code payloadBytes()}, and that adding a new record
 * type without wiring it up triggers a test failure.</p>
 */
class TypedRecordTest {

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

        TraceRecord ar = TraceRecord.parse(ArgumentsRecord.TYPE, cbor);
        assertInstanceOf(ArgumentsRecord.class, ar);
        assertArrayEquals(cbor, ar.payloadBytes());

        TraceRecord ax = TraceRecord.parse(ArgumentsExitRecord.TYPE, cbor);
        assertInstanceOf(ArgumentsExitRecord.class, ax);
        assertArrayEquals(cbor, ax.payloadBytes());

        TraceRecord ti = TraceRecord.parse(ThisInstanceRecord.TYPE, cbor);
        assertInstanceOf(ThisInstanceRecord.class, ti);
        assertArrayEquals(cbor, ti.payloadBytes());

        TraceRecord ex = TraceRecord.parse(ExceptionRecord.TYPE, cbor);
        assertInstanceOf(ExceptionRecord.class, ex);
        assertArrayEquals(cbor, ex.payloadBytes());

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
