package com.github.gabert.deepflow.agent.recording;

import com.github.gabert.deepflow.codec.Codec;

import java.io.IOException;
import java.util.Map;

/**
 * Encodes captured values to CBOR with an optional size cap.
 *
 * <p>When {@code maxValueSize > 0} and the encoded payload exceeds it, the
 * value is replaced by a fixed-shape <i>truncation marker</i>:
 *
 * <pre>{ "__truncated": true, "original_size": &lt;encoded length in bytes&gt; }</pre>
 *
 * This shape is part of the wire contract with the Python formatter. Do not
 * change keys or types without updating
 * {@code deepflow-formater/deepflow/agent_dump_processor.py}.</p>
 */
public final class ValueEncoder {
    private final int maxValueSize;

    public ValueEncoder(int maxValueSize) {
        this.maxValueSize = maxValueSize;
    }

    public byte[] encode(Object value) throws IOException {
        byte[] encoded = Codec.encode(value);
        if (maxValueSize > 0 && encoded.length > maxValueSize) {
            return Codec.encode(truncationMarker(encoded.length));
        }
        return encoded;
    }

    private static Map<String, Object> truncationMarker(int originalSize) {
        return Map.of("__truncated", true, "original_size", originalSize);
    }
}
