package com.github.gabert.deepflow.recorder.record;

public final class RecordType {
    public static final byte METHOD_START = 0x01;
    public static final byte ARGUMENTS   = 0x02;
    public static final byte RETURN      = 0x03;
    public static final byte EXCEPTION   = 0x04;
    public static final byte METHOD_END     = 0x05;
    public static final byte THIS_INSTANCE     = 0x06;
    public static final byte THIS_INSTANCE_REF = 0x07;

    static final int HEADER_SIZE            = 5; // 1 byte type + 4 bytes length
    static final int SIGNATURE_LENGTH_SIZE = Short.BYTES;
    static final int THREAD_NAME_LENGTH_SIZE = Short.BYTES;
    static final int TIMESTAMP_SIZE        = Long.BYTES;
    static final int CALLER_LINE_SIZE      = Integer.BYTES;
    static final int CALL_DEPTH_SIZE       = Integer.BYTES;

    private RecordType() {}
}
