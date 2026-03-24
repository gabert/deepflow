package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.FieldIds;
import com.github.gabert.deepflow.recorder.record.MethodEndData;
import com.github.gabert.deepflow.recorder.record.MethodStartData;
import com.github.gabert.deepflow.recorder.record.RecordReader;
import com.github.gabert.deepflow.recorder.record.RecordType;

import java.io.IOException;

import com.github.gabert.deepflow.recorder.record.TraceRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RecordRenderer {
    private static final String DELIMITER = ";";

    private RecordRenderer() {}

    public record Result(String threadName, List<String> lines) {}

    public static Result render(byte[] data) {
        List<TraceRecord> records = RecordReader.readAll(data);
        List<String> lines = new ArrayList<>();
        String threadName = null;

        for (TraceRecord record : records) {
            switch (record.type()) {
                case RecordType.METHOD_START -> {
                    MethodStartData meta = RecordReader.decodeMethodStart(record);
                    threadName = meta.threadName;
                    if (meta.sessionId != null) {
                        lines.add(line("SI", meta.sessionId));
                    }
                    lines.add(line("MS", meta.signature));
                    lines.add(line("TN", meta.threadName));
                    lines.add(line("CD", String.valueOf(meta.depth)));
                    lines.add(line("TS", String.valueOf(meta.timestamp)));
                    lines.add(line("CL", String.valueOf(meta.callerLine)));
                }
                case RecordType.THIS_INSTANCE -> {
                    lines.add(line("TI", decodeCborPayload(record.payload())));
                }
                case RecordType.THIS_INSTANCE_REF -> {
                    long objectId = RecordReader.getLong(record.payload(), 0);
                    lines.add(line("TI", String.valueOf(objectId)));
                }
                case RecordType.ARGUMENTS -> {
                    lines.add(line("AR", decodeArgumentsPayload(record.payload())));
                }
                case RecordType.RETURN -> {
                    if (record.payload().length == 0) {
                        lines.add(line("RT", "VOID"));
                    } else {
                        lines.add(line("RT", "VALUE"));
                        lines.add(line("RE", decodeCborPayload(record.payload())));
                    }
                }
                case RecordType.EXCEPTION -> {
                    lines.add(line("RT", "EXCEPTION"));
                    lines.add(line("RE", decodeCborPayload(record.payload())));
                }
                case RecordType.METHOD_END -> {
                    MethodEndData meta = RecordReader.decodeMethodEnd(record);
                    threadName = meta.threadName;
                    lines.add(line("TN", meta.threadName));
                    lines.add(line("TE", String.valueOf(meta.timestamp)));
                }
            }
        }

        return new Result(threadName, lines);
    }

    // --- Utilities ---

    private static String line(String tag, String value) {
        return tag + DELIMITER + value;
    }

    private static String decodeArgumentsPayload(byte[] payload) {
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

    private static String decodeCborPayload(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }
}
