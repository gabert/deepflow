package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.FieldIds;
import com.github.gabert.deepflow.recorder.record.ArgumentsExitRecord;
import com.github.gabert.deepflow.recorder.record.ArgumentsRecord;
import com.github.gabert.deepflow.recorder.record.ExceptionRecord;
import com.github.gabert.deepflow.recorder.record.MethodEndRecord;
import com.github.gabert.deepflow.recorder.record.MethodStartRecord;
import com.github.gabert.deepflow.recorder.record.RecordReader;
import com.github.gabert.deepflow.recorder.record.ReturnRecord;
import com.github.gabert.deepflow.recorder.record.ThisInstanceRecord;
import com.github.gabert.deepflow.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.deepflow.recorder.record.TraceRecord;
import com.github.gabert.deepflow.recorder.record.VersionRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes a binary record stream and renders it as semicolon-delimited
 * {@code TAG;value} lines, filtered by a configured {@code emit_tags} set.
 *
 * <p>Type-specific tag emission lives next to each {@link TraceRecord}
 * implementation conceptually — but Java 17 doesn't yet have standard
 * pattern-matching switches over sealed types, so the dispatch is an
 * {@code instanceof} chain in {@link #tagsFor(TraceRecord)}. Adding a new
 * record type means: add the class to {@code TraceRecord.permits}, add a
 * {@code case} in {@code TraceRecord.parse}, and add an {@code instanceof}
 * branch here.</p>
 */
public final class RecordRenderer {
    private static final String DELIMITER = ";";
    private static final Set<String> ALL_TAGS = Set.of(
            "VR", "MS", "SI", "TN", "RI", "TS", "CL", "TI", "AR", "AX", "RT", "RE", "TE");

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
            for (TagEntry entry : tagsFor(record)) {
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

    // --- Per-type tag rendering ---

    private static List<TagEntry> tagsFor(TraceRecord record) {
        if (record instanceof VersionRecord v) {
            return List.of(tag("VR", v.major() + "." + v.minor()));
        }
        if (record instanceof MethodStartRecord m) {
            return tagsForMethodStart(m);
        }
        if (record instanceof MethodEndRecord m) {
            return tagsForMethodEnd(m);
        }
        if (record instanceof ThisInstanceRecord t) {
            return List.of(tag("TI", decodeCbor(t.cbor())));
        }
        if (record instanceof ThisInstanceRefRecord t) {
            return List.of(tag("TI", String.valueOf(t.objectId())));
        }
        if (record instanceof ArgumentsRecord a) {
            return List.of(tag("AR", decodeArgumentsPayload(a.cbor())));
        }
        if (record instanceof ArgumentsExitRecord a) {
            return List.of(tag("AX", decodeArgumentsPayload(a.cbor())));
        }
        if (record instanceof ReturnRecord r) {
            return r.isVoid()
                    ? List.of(tag("RT", "VOID"))
                    : List.of(tag("RT", "VALUE"), tag("RE", decodeCbor(r.cbor())));
        }
        if (record instanceof ExceptionRecord e) {
            return List.of(tag("RT", "EXCEPTION"), tag("RE", decodeCbor(e.cbor())));
        }
        // Sealed permits clause guarantees this is unreachable in correct code.
        throw new IllegalStateException("Unhandled TraceRecord subtype: " + record.getClass());
    }

    private static List<TagEntry> tagsForMethodStart(MethodStartRecord m) {
        List<TagEntry> entries = new ArrayList<>();
        entries.add(tag("TS", String.valueOf(m.timestamp())));
        if (m.sessionId() != null) entries.add(tag("SI", m.sessionId()));
        entries.add(tag("MS", m.signature()));
        entries.add(tag("TN", m.threadName()));
        entries.add(tag("RI", String.valueOf(m.requestId())));
        entries.add(tag("CL", String.valueOf(m.callerLine())));
        entries.add(threadName(m.threadName()));
        return entries;
    }

    private static List<TagEntry> tagsForMethodEnd(MethodEndRecord m) {
        return List.of(
                tag("TE", String.valueOf(m.timestamp())),
                tag("TN", m.threadName()),
                tag("RI", String.valueOf(m.requestId())),
                threadName(m.threadName()));
    }

    // --- TagEntry ---

    private record TagEntry(String tag, String value, String threadName) {}

    private static TagEntry tag(String tag, String value) {
        return new TagEntry(tag, value, null);
    }

    private static TagEntry threadName(String name) {
        return new TagEntry("_threadName", "", name);
    }

    // --- CBOR-payload decoders ---

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

    private static String decodeCbor(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }
}
