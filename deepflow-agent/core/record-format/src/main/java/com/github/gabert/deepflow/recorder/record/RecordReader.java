package com.github.gabert.deepflow.recorder.record;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RecordReader {

    private RecordReader() {}

    // --- Public API ---

    public static List<RawFrame> readAll(byte[] data) {
        List<RawFrame> records = new ArrayList<>();
        int pos = 0;
        while (pos + RecordType.HEADER_SIZE <= data.length) {
            byte type = data[pos];
            int length = BinaryUtil.getInt(data, pos + 1);
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
            records.add(new RawFrame(type, payload));
            pos += length;
        }
        return records;
    }

    public static List<RawFrame> readAll(InputStream in) throws IOException {
        return readAll(in.readAllBytes());
    }

    public static MethodStartData decodeMethodStart(RawFrame record) {
        byte[] payload = record.payload();
        int pos = 0;
        int sessionIdLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SESSION_ID_LENGTH_SIZE;
        String sessionId = sessionIdLen > 0
                ? new String(payload, pos, sessionIdLen, StandardCharsets.UTF_8)
                : null;
        pos += sessionIdLen;
        int sigLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SIGNATURE_LENGTH_SIZE;
        String signature = new String(payload, pos, sigLen, StandardCharsets.UTF_8);
        pos += sigLen;
        int threadLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, threadLen, StandardCharsets.UTF_8);
        pos += threadLen;
        long timestamp = BinaryUtil.getLong(payload, pos);
        pos += RecordType.TIMESTAMP_SIZE;
        int callerLine = BinaryUtil.getInt(payload, pos);
        pos += RecordType.CALLER_LINE_SIZE;
        long requestId = BinaryUtil.getLong(payload, pos);
        return new MethodStartData(sessionId, signature, threadName, timestamp, callerLine, requestId);
    }

    public static MethodEndData decodeMethodEnd(RawFrame record) {
        byte[] payload = record.payload();
        int pos = 0;
        int sessionIdLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.SESSION_ID_LENGTH_SIZE;
        String sessionId = sessionIdLen > 0
                ? new String(payload, pos, sessionIdLen, StandardCharsets.UTF_8)
                : null;
        pos += sessionIdLen;
        int threadLen = BinaryUtil.getShort(payload, pos);
        pos += RecordType.THREAD_NAME_LENGTH_SIZE;
        String threadName = new String(payload, pos, threadLen, StandardCharsets.UTF_8);
        pos += threadLen;
        long timestamp = BinaryUtil.getLong(payload, pos);
        pos += RecordType.TIMESTAMP_SIZE;
        long requestId = BinaryUtil.getLong(payload, pos);
        return new MethodEndData(sessionId, timestamp, threadName, requestId);
    }
}
