package com.github.gabert.arachna.trace.recorder.record;

/**
 * Pre-marshaled frame body paired with its type byte. Writer-side only —
 * decoding always goes through {@link TraceRecord#parse}. The payload must
 * come from the matching record class's static payload builder (e.g.
 * {@link MethodStartRecord#payloadFrom}), which keeps the marshaling's
 * single source of truth in the record class; the bytes are identical to
 * that record's {@code payloadBytes()}, so the read side cannot tell the
 * difference.
 */
public record RawFrame(byte typeByte, byte[] payloadBytes) implements FrameSource {
}
