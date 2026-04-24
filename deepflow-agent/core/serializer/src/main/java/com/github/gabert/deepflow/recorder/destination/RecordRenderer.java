
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class RecordRenderer {
    private static final String DELIMITER = ";";
    private static final Set<String> ALL_TAGS = Set.of(
            "VR", "MS", "SI", "TN", "CI", "PI", "TS", "CL", "TI", "AR", "AX", "RT", "RE", "TE");

    private static final Map<Byte, Function<TraceRecord, List<TagEntry>>> HANDLERS = buildHandlers();

    private RecordRenderer() {}

    public record Result(String threadName, List<String> lines) {}

    public static Result render(byte[] data) {
        return render(data, ALL_TAGS);
    }

    public static Result render(byte[] data, Set<String> emitTags) {
        List<TraceRecord> records = RecordReader.readAll(data);
        List<String> lines = new ArrayList<>();
        String threadName = null;

        for (TraceRecord record : records) {
            Function<TraceRecord, List<TagEntry>> handler = HANDLERS.get(record.type());
            if (handler == null) continue;

            for (TagEntry entry : handler.apply(record)) {
                if (entry.threadName() != null) {
                    threadName = entry.threadName();
                }
                if ("MS".equals(entry.tag()) || "VR".equals(entry.tag()) || emitTags.contains(entry.tag())) {
                    lines.add(entry.tag() + DELIMITER + entry.value());
                }
            }
        }

        return new Result(threadName, lines);
    }

    // --- Handler registry ---

    private static Map<Byte, Function<TraceRecord, List<TagEntry>>> buildHandlers() {
        Map<Byte, Function<TraceRecord, List<TagEntry>>> map = new HashMap<>();

        map.put(RecordType.VERSION, record -> {
            short major = (short) ((record.payload()[0] << 8) | (record.payload()[1] & 0xFF));
            short minor = (short) ((record.payload()[2] << 8) | (record.payload()[3] & 0xFF));
            return List.of(tag("VR", major + "." + minor));
        });

        map.put(RecordType.METHOD_START, record -> {
            MethodStartData m = RecordReader.decodeMethodStart(record);
            List<TagEntry> entries = new ArrayList<>();
            if (m.sessionId != null) entries.add(tag("SI", m.sessionId));
            entries.add(tag("MS", m.signature));
            entries.add(tag("TN", m.threadName));
            entries.add(tag("CI", String.valueOf(m.callId)));
            entries.add(tag("PI", String.valueOf(m.parentCallId)));
            entries.add(tag("TS", String.valueOf(m.timestamp)));
            entries.add(tag("CL", String.valueOf(m.callerLine)));
            entries.add(threadName(m.threadName));
            return entries;
        });

        map.put(RecordType.THIS_INSTANCE, record ->
                List.of(tag("TI", decodeCborPayload(record.payload()))));

        map.put(RecordType.THIS_INSTANCE_REF, record ->
                List.of(tag("TI", String.valueOf(RecordReader.getLong(record.payload(), 0)))));

        map.put(RecordType.ARGUMENTS, record ->
                List.of(tag("AR", decodeArgumentsPayload(record.payload()))));

        map.put(RecordType.ARGUMENTS_EXIT, record ->
                List.of(tag("AX", decodeArgumentsPayload(record.payload()))));

        map.put(RecordType.RETURN, record -> {
            if (record.payload().length == 0) {
                return List.of(tag("RT", "VOID"));
            }
            return List.of(
                    tag("RT", "VALUE"),
                    tag("RE", decodeCborPayload(record.payload())));
        });

        map.put(RecordType.EXCEPTION, record -> List.of(
                tag("RT", "EXCEPTION"),
                tag("RE", decodeCborPayload(record.payload()))));

        map.put(RecordType.METHOD_END, record -> {
            MethodEndData m = RecordReader.decodeMethodEnd(record);
            return List.of(
                    tag("TN", m.threadName),
                    tag("TE", String.valueOf(m.timestamp)),
                    threadName(m.threadName));
        });

        return Map.copyOf(map);
    }

    // --- TagEntry ---

    private record TagEntry(String tag, String value, String threadName) {}

    private static TagEntry tag(String tag, String value) {
        return new TagEntry(tag, value, null);
    }

    private static TagEntry threadName(String name) {
        return new TagEntry("_threadName", "", name);
    }

    // --- Decoders ---

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
