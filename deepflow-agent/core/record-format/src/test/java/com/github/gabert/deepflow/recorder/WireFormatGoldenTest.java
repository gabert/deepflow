package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.recorder.record.MethodEndData;
import com.github.gabert.deepflow.recorder.record.MethodStartData;
import com.github.gabert.deepflow.recorder.record.RecordReader;
import com.github.gabert.deepflow.recorder.record.RecordType;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import com.github.gabert.deepflow.recorder.record.TraceRecord;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the exact on-the-wire byte layout of every record type.
 *
 * <p>Round-trip tests (in {@link RecordWriterReaderTest}) only verify that the
 * writer and reader agree with each other — a refactor that changes both in
 * the same way would still pass them. These tests instead hardcode the
 * expected byte sequence so any change to the wire format must update this
 * file deliberately.</p>
 *
 * <p>The wire format is a contract with the Python formatter and any historical
 * {@code .dft} files on disk. Do not change a hex string here without
 * updating the formatter (and accepting that older traces become unreadable).</p>
 *
 * <p>Frame layout: {@code [type:1][payloadLen:4][payload:N]} (5-byte header).
 * All multi-byte integers are big-endian. Strings are UTF-8 with a {@code short}
 * length prefix.</p>
 */
class WireFormatGoldenTest {

    private static final HexFormat HEX = HexFormat.of();

    // ============================================================
    //  VERSION (0x09)
    //   payload = [major:short][minor:short]
    // ============================================================

    @Test
    void version_1_2_layout() {
        byte[] bytes = RecordWriter.version((short) 1, (short) 2);

        // 09          type=VERSION
        // 00 00 00 04 payload length = 4
        // 00 01       major = 1
        // 00 02       minor = 2
        assertArrayEquals(HEX.parseHex("09000000040001 0002".replace(" ", "")), bytes);
    }

    @Test
    void version_currentDefaultLayout() {
        // Pin the agent's actual version constants. If we bump them, this
        // test must change deliberately.
        byte[] bytes = RecordWriter.version();

        byte[] expected = new byte[] {
                0x09,
                0x00, 0x00, 0x00, 0x04,
                0x00, (byte) RecordType.VERSION_MAJOR,
                0x00, (byte) RecordType.VERSION_MINOR
        };
        assertArrayEquals(expected, bytes);
    }

    // ============================================================
    //  METHOD_START (0x01)
    //   payload = [sidLen:short][sid][sigLen:short][sig]
    //             [tnLen:short][tn][ts:long][callerLine:int][requestId:long]
    // ============================================================

    @Test
    void methodStart_layoutWithKnownFields() {
        byte[] bytes = RecordWriter.logEntrySimple(
                "S",                            // sessionId
                "M",                            // signature
                "T",                            // threadName
                0x0102030405060708L,            // timestamp
                0x0A0B0C0D,                     // callerLine
                0x1112131415161718L);           // requestId

        // 01                        type = METHOD_START
        // 00 00 00 1D               payload length = 29
        // 00 01 53                  sessionId "S"
        // 00 01 4D                  signature "M"
        // 00 01 54                  threadName "T"
        // 01 02 03 04 05 06 07 08   timestamp
        // 0A 0B 0C 0D               callerLine
        // 11 12 13 14 15 16 17 18   requestId
        String expected = "01" + "0000001D"
                + "0001" + "53"
                + "0001" + "4D"
                + "0001" + "54"
                + "0102030405060708"
                + "0A0B0C0D"
                + "1112131415161718";
        assertArrayEquals(HEX.parseHex(expected), bytes);
    }

    @Test
    void methodStart_nullSessionIdEncodesAsZeroLength() {
        byte[] bytes = RecordWriter.logEntrySimple(
                null, "M", "T", 0L, 0, 0L);

        // 01                        type = METHOD_START
        // 00 00 00 1C               payload length = 28
        // 00 00                     sessionIdLen = 0  (no sessionId bytes)
        // 00 01 4D                  signature "M"
        // 00 01 54                  threadName "T"
        // 00 00 00 00 00 00 00 00   timestamp = 0
        // 00 00 00 00               callerLine = 0
        // 00 00 00 00 00 00 00 00   requestId = 0
        String expected = "01" + "0000001C"
                + "0000"
                + "0001" + "4D"
                + "0001" + "54"
                + "0000000000000000"
                + "00000000"
                + "0000000000000000";
        assertArrayEquals(HEX.parseHex(expected), bytes);
    }

    // ============================================================
    //  METHOD_END (0x05)
    //   payload = [sidLen:short][sid][tnLen:short][tn]
    //             [ts:long][requestId:long]
    // ============================================================

    @Test
    void methodEnd_layoutWithKnownFields() {
        byte[] bytes = RecordWriter.methodEnd(
                "S", "T", 0x0102030405060708L, 0x1112131415161718L);

        // 05                        type = METHOD_END
        // 00 00 00 16               payload length = 22
        // 00 01 53                  sessionId "S"
        // 00 01 54                  threadName "T"
        // 01 02 03 04 05 06 07 08   timestamp
        // 11 12 13 14 15 16 17 18   requestId
        String expected = "05" + "00000016"
                + "0001" + "53"
                + "0001" + "54"
                + "0102030405060708"
                + "1112131415161718";
        assertArrayEquals(HEX.parseHex(expected), bytes);
    }

    @Test
    void methodEnd_nullSessionIdEncodesAsZeroLength() {
        byte[] bytes = RecordWriter.methodEnd(null, "T", 0L, 0L);

        String expected = "05" + "00000015"
                + "0000"
                + "0001" + "54"
                + "0000000000000000"
                + "0000000000000000";
        assertArrayEquals(HEX.parseHex(expected), bytes);
    }

    // ============================================================
    //  THIS_INSTANCE (0x06) — wraps a CBOR payload as-is
    // ============================================================

    @Test
    void thisInstance_framesPayloadAsIs() {
        byte[] cbor = HEX.parseHex("DEADBEEF");
        byte[] bytes = RecordWriter.thisInstance(cbor);

        // 06            type = THIS_INSTANCE
        // 00 00 00 04   length = 4
        // DE AD BE EF   payload
        assertArrayEquals(HEX.parseHex("0600000004DEADBEEF"), bytes);
    }

    // ============================================================
    //  THIS_INSTANCE_REF (0x07)
    //   payload = [objectId:long]
    // ============================================================

    @Test
    void thisInstanceRef_layoutForObjectId() {
        byte[] bytes = RecordWriter.thisInstanceRef(0x123456789ABCDEF0L);

        // 07                        type = THIS_INSTANCE_REF
        // 00 00 00 08               length = 8
        // 12 34 56 78 9A BC DE F0   objectId
        assertArrayEquals(HEX.parseHex("0700000008123456789ABCDEF0"), bytes);
    }

    // ============================================================
    //  ARGUMENTS (0x02) / ARGUMENTS_EXIT (0x08)
    //   payload = [cbor bytes]
    // ============================================================

    @Test
    void arguments_framesPayloadAsIs() {
        byte[] cbor = HEX.parseHex("CAFEBABE");
        byte[] bytes = RecordWriter.arguments(cbor);

        assertArrayEquals(HEX.parseHex("0200000004CAFEBABE"), bytes);
    }

    @Test
    void argumentsExit_framesPayloadAsIs() {
        byte[] cbor = HEX.parseHex("CAFEBABE");
        byte[] bytes = RecordWriter.argumentsExit(cbor);

        assertArrayEquals(HEX.parseHex("0800000004CAFEBABE"), bytes);
    }

    // ============================================================
    //  RETURN (0x03)
    //   value: payload = [cbor bytes]
    //   void:  payload = []
    // ============================================================

    @Test
    void returnValue_framesPayloadAsIs() {
        byte[] cbor = HEX.parseHex("DEADBEEF");
        byte[] bytes = RecordWriter.returnValue(cbor);

        assertArrayEquals(HEX.parseHex("0300000004DEADBEEF"), bytes);
    }

    @Test
    void returnVoid_isZeroLengthPayload() {
        byte[] bytes = RecordWriter.returnVoid();

        // 03            type = RETURN
        // 00 00 00 00   length = 0
        // (no payload)
        assertArrayEquals(HEX.parseHex("0300000000"), bytes);
    }

    // ============================================================
    //  EXCEPTION (0x04)
    //   payload = [cbor bytes]
    // ============================================================

    @Test
    void exception_framesPayloadAsIs() {
        byte[] cbor = HEX.parseHex("BADBADBA");
        byte[] bytes = RecordWriter.exception(cbor);

        assertArrayEquals(HEX.parseHex("0400000004BADBADBA"), bytes);
    }

    // ============================================================
    //  Reader symmetry: every golden encoding parses back to the
    //  same logical fields. Catches regressions where a reader
    //  drifts independently of the writer.
    // ============================================================

    @Test
    void methodStart_parsesBackToSameFields() {
        byte[] bytes = RecordWriter.logEntrySimple(
                "session", "sig", "thread",
                0x1122334455667788L, 42, 0x99AABBCCDDEEFF00L);

        List<TraceRecord> records = RecordReader.readAll(bytes);
        assertEquals(1, records.size());
        assertEquals(RecordType.METHOD_START, records.get(0).type());

        MethodStartData parsed = RecordReader.decodeMethodStart(records.get(0));
        assertEquals("session", parsed.sessionId);
        assertEquals("sig", parsed.signature);
        assertEquals("thread", parsed.threadName);
        assertEquals(0x1122334455667788L, parsed.timestamp);
        assertEquals(42, parsed.callerLine);
        assertEquals(0x99AABBCCDDEEFF00L, parsed.requestId);
    }

    @Test
    void methodEnd_parsesBackToSameFields() {
        byte[] bytes = RecordWriter.methodEnd(
                "session", "thread", 0x1122334455667788L, 0x99AABBCCDDEEFF00L);

        List<TraceRecord> records = RecordReader.readAll(bytes);
        assertEquals(1, records.size());
        assertEquals(RecordType.METHOD_END, records.get(0).type());

        MethodEndData parsed = RecordReader.decodeMethodEnd(records.get(0));
        assertEquals("session", parsed.sessionId);
        assertEquals("thread", parsed.threadName);
        assertEquals(0x1122334455667788L, parsed.timestamp);
        assertEquals(0x99AABBCCDDEEFF00L, parsed.requestId);
    }

    @Test
    void methodStart_nullSessionIdParsesBackAsNull() {
        byte[] bytes = RecordWriter.logEntrySimple(
                null, "sig", "thread", 0L, 0, 0L);

        MethodStartData parsed = RecordReader.decodeMethodStart(
                RecordReader.readAll(bytes).get(0));
        assertNull(parsed.sessionId);
    }

    @Test
    void thisInstanceRef_parsesBackToSameId() {
        byte[] bytes = RecordWriter.thisInstanceRef(0x123456789ABCDEF0L);

        List<TraceRecord> records = RecordReader.readAll(bytes);
        assertEquals(1, records.size());
        assertEquals(RecordType.THIS_INSTANCE_REF, records.get(0).type());
        assertEquals(0x123456789ABCDEF0L, RecordReader.getLong(records.get(0).payload(), 0));
    }
}
