package com.github.gabert.deepflow.recorder.record;

/**
 * A parsed binary frame: the type discriminator byte plus the raw payload bytes,
 * before any type-specific decoding.
 *
 * <p>Used internally by the reader to hold the result of frame parsing while the
 * payload is still opaque. Type-specific decode happens via
 * {@link RecordReader#decodeMethodStart(RawFrame)} etc.</p>
 */
public final class RawFrame {
    private final byte type;
    private final byte[] payload;

    public RawFrame(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public byte type() {
        return type;
    }

    public byte[] payload() {
        return payload;
    }

    @Override
    public String toString() {
        return "RawFrame{type=0x" + String.format("%02x", type)
                + ", payloadLength=" + payload.length + "}";
    }
}
