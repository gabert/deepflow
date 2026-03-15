package com.github.gabert.deepflow.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RecordReader {

    private RecordReader() {}

    // --- Public API ---

    public static List<Record> readAll(byte[] data) {
        List<Record> records = new ArrayList<>();
        int pos = 0;
        while (pos + RecordType.HEADER_SIZE <= data.length) {
            byte type = data[pos];
            int length = getInt(data, pos + 1);
            pos += RecordType.HEADER_SIZE;

            if (length < 0) {
                throw new IllegalArgumentException(
                        "Negative record length " + length
                        + " at offset " + (pos - RecordType.HEADER_SIZE));
            }

            if (pos + length > data.length) {
                throw new IllegalArgumentException(
                        "Truncated record at offset " + (pos - RecordType.HEADER_SIZE)
                        + ": declared length " + length
                        + ", available " + (data.length - pos));
            }

            byte[] payload = Arrays.copyOfRange(data, pos, pos + length);
            records.add(new Record(type, payload));
            pos += length;
        }
        return records;
    }

    public static List<Record> readAll(InputStream in) throws IOException {
        return readAll(in.readAllBytes());
    }

    public static MethodStartData decodeMethodStart(Record record) {
        byte[] payload = record.payload();
        int pos = 0;
        int sigLen = getShort(payload, pos);
        pos += RecordType.SIGNATURE_LENGTH_SIZE;
        String signature = new String(payload, pos, sigLen, StandardCharsets.UTF_8);
        pos += sigLen;
        int threadLen = getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, threadLen, StandardCharsets.UTF_8);
        pos += threadLen;
        long timestamp = getLong(payload, pos);
        pos += RecordType.TIMESTAMP_SIZE;
        int callerLine = getInt(payload, pos);
        pos += RecordType.CALLER_LINE_SIZE;
        int depth = getInt(payload, pos);
        return new MethodStartData(signature, threadName, timestamp, callerLine, depth);
    }

    public static MethodEndData decodeMethodEnd(Record record) {
        byte[] payload = record.payload();
        int pos = 0;
        int threadLen = getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, threadLen, StandardCharsets.UTF_8);
        pos += threadLen;
        long timestamp = getLong(payload, pos);
        return new MethodEndData(timestamp, threadName);
    }

    // --- Binary field readers ---

    private static int getShort(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8)
             | (buf[pos + 1] & 0xFF);
    }

    private static int getInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24)
             | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) << 8)
             | (buf[pos + 3] & 0xFF);
    }

    static long getLong(byte[] buf, int pos) {
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