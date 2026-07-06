package com.github.gabert.arachna.trace.recorder.record;

/**
 * Anything that can be written as one wire frame: a type byte plus payload
 * bytes. {@link TraceRecord} is the typed, parseable implementation;
 * {@link RawFrame} carries a payload marshaled ahead of time (e.g. from
 * cached UTF-8 bytes on the agent's hot path).
 */
public interface FrameSource {

    /** Single-byte record-type discriminator (matches {@link RecordType} constants). */
    byte typeByte();

    /** The body of this frame — everything after the 5-byte frame header. */
    byte[] payloadBytes();
}
