package com.github.gabert.deepflow.recorder.record;

/**
 * One trace record on the wire — a single discriminated frame written by the
 * agent and read by destinations / formatters.
 *
 * <p>The set of permitted implementations is closed: each implementation owns
 * its type byte, its payload marshaling, and its parser. Adding a new record
 * type means adding one class to the {@code permits} clause and one case to
 * {@link #parse(byte, byte[])}; the compiler's exhaustiveness check on
 * sealed switches guarantees no rendering or dispatching site is missed.</p>
 *
 * <p>Frame layout: {@code [type:1][payloadLen:4][payload:N]} (5-byte header).
 * {@link #toFrame()} builds the full frame; {@link #payloadBytes()} returns
 * just the body.</p>
 */
public sealed interface TraceRecord
        permits VersionRecord,
                MethodStartRecord,
                MethodEndRecord,
                ArgumentsRecord,
                ArgumentsExitRecord,
                ReturnRecord,
                ExceptionRecord,
                ThisInstanceRecord,
                ThisInstanceRefRecord {

    /** Single-byte record-type discriminator (matches {@link RecordType} constants). */
    byte typeByte();

    /** The body of this record — everything after the 5-byte frame header. */
    byte[] payloadBytes();

    /** The full on-the-wire frame: 1 byte type + 4 bytes length + payload. */
    default byte[] toFrame() {
        byte[] payload = payloadBytes();
        byte[] frame = new byte[RecordType.HEADER_SIZE + payload.length];
        frame[0] = typeByte();
        BinaryUtil.putInt(frame, 1, payload.length);
        System.arraycopy(payload, 0, frame, RecordType.HEADER_SIZE, payload.length);
        return frame;
    }

    /**
     * Parse a single record from its type byte and payload bytes.
     *
     * @throws IllegalArgumentException if {@code typeByte} is not a known record type
     */
    static TraceRecord parse(byte typeByte, byte[] payload) {
        return switch (typeByte) {
            case RecordType.VERSION           -> VersionRecord.parse(payload);
            case RecordType.METHOD_START      -> MethodStartRecord.parse(payload);
            case RecordType.METHOD_END        -> MethodEndRecord.parse(payload);
            case RecordType.ARGUMENTS         -> ArgumentsRecord.parse(payload);
            case RecordType.ARGUMENTS_EXIT    -> ArgumentsExitRecord.parse(payload);
            case RecordType.RETURN            -> ReturnRecord.parse(payload);
            case RecordType.EXCEPTION         -> ExceptionRecord.parse(payload);
            case RecordType.THIS_INSTANCE     -> ThisInstanceRecord.parse(payload);
            case RecordType.THIS_INSTANCE_REF -> ThisInstanceRefRecord.parse(payload);
            default -> throw new IllegalArgumentException(
                    "Unknown record type byte: 0x" + String.format("%02X", typeByte));
        };
    }
}
