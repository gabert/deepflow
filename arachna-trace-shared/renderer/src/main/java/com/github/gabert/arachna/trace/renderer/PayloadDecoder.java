package com.github.gabert.arachna.trace.renderer;

import com.github.gabert.arachna.trace.codec.Codec;
import com.github.gabert.arachna.trace.codec.envelope.FieldIds;

import java.io.IOException;
import java.util.Map;

/**
 * Decodes the CBOR payload of a value-carrying record (TI, AR, AX, RE) to a
 * human-readable JSON string. This is the single place where "payload bytes
 * to JSON text" is defined; {@link RecordRenderer} uses it for tag lines and
 * the processor's parser uses it for ClickHouse payload columns.
 *
 * <p>Decode failures never propagate: a poison payload renders as a
 * {@code <decode error: ...>} marker so one bad value cannot sink a batch.</p>
 */
public final class PayloadDecoder {

    private PayloadDecoder() {}

    /** Decode a CBOR payload (TI / RE) to readable JSON. */
    public static String toJson(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }

    /**
     * Decode an arguments CBOR payload (AR / AX) to readable JSON. The
     * arguments map is wrapped in its own identity envelope by the agent;
     * only the inner value (the name-to-argument map) is rendered.
     */
    public static String argumentsToJson(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            if (decoded instanceof Map<?, ?> envelope) {
                Object args = getEnvelopeValue(envelope, FieldIds.VALUE);
                if (args != null) {
                    return Codec.toReadableJson(args);
                }
            }
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }

    private static Object getEnvelopeValue(Map<?, ?> envelope, int fieldId) {
        Object value = envelope.get(fieldId);
        if (value == null) {
            value = envelope.get(String.valueOf(fieldId));
        }
        return value;
    }
}
