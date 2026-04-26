package com.github.gabert.deepflow.recorder.record;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a stream of binary frames into {@link TraceRecord}s.
 *
 * <p>The frame loop is the only place that reads the 5-byte header
 * ({@code [type:1][payloadLen:4]}); type-specific decoding is delegated to
 * the per-record {@code parse(payload)} static methods via
 * {@link TraceRecord#parse(byte, byte[])}.</p>
 */
public final class RecordReader {

    private RecordReader() {}

    public static List<TraceRecord> readAll(byte[] data) {
        List<TraceRecord> records = new ArrayList<>();
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
            records.add(TraceRecord.parse(type, payload));
            pos += length;
        }
        return records;
    }

    public static List<TraceRecord> readAll(InputStream in) throws IOException {
        return readAll(in.readAllBytes());
    }
}
