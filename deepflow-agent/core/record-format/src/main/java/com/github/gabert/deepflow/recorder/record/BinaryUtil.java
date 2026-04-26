package com.github.gabert.deepflow.recorder.record;

/**
 * Big-endian read/write helpers for the wire format.
 *
 * <p>The agent's record format is defined as fixed-width big-endian
 * integers with no native dependencies. These helpers are the single
 * source of truth for that encoding — used by both the writer and the
 * reader so the two cannot drift independently.</p>
 */
public final class BinaryUtil {

    private BinaryUtil() {}

    // --- Writers (return next position) ---

    public static int putShort(byte[] buf, int pos, short value) {
        buf[pos]     = (byte) (value >>> 8);
        buf[pos + 1] = (byte) value;
        return pos + 2;
    }

    public static int putInt(byte[] buf, int pos, int value) {
        buf[pos]     = (byte) (value >>> 24);
        buf[pos + 1] = (byte) (value >>> 16);
        buf[pos + 2] = (byte) (value >>> 8);
        buf[pos + 3] = (byte) value;
        return pos + 4;
    }

    public static int putLong(byte[] buf, int pos, long value) {
        buf[pos]     = (byte) (value >>> 56);
        buf[pos + 1] = (byte) (value >>> 48);
        buf[pos + 2] = (byte) (value >>> 40);
        buf[pos + 3] = (byte) (value >>> 32);
        buf[pos + 4] = (byte) (value >>> 24);
        buf[pos + 5] = (byte) (value >>> 16);
        buf[pos + 6] = (byte) (value >>> 8);
        buf[pos + 7] = (byte) value;
        return pos + 8;
    }

    // --- Readers ---

    public static int getShort(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8)
             | (buf[pos + 1] & 0xFF);
    }

    public static int getInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24)
             | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) << 8)
             | (buf[pos + 3] & 0xFF);
    }

    public static long getLong(byte[] buf, int pos) {
        return ((long)(buf[pos] & 0xFF) << 56)
             | ((long)(buf[pos + 1] & 0xFF) << 48)
             | ((long)(buf[pos + 2] & 0xFF) << 40)
             | ((long)(buf[pos + 3] & 0xFF) << 32)
             | ((long)(buf[pos + 4] & 0xFF) << 24)
             | ((long)(buf[pos + 5] & 0xFF) << 16)
             | ((long)(buf[pos + 6] & 0xFF) << 8)
             | ((long)(buf[pos + 7] & 0xFF));
    }
}
