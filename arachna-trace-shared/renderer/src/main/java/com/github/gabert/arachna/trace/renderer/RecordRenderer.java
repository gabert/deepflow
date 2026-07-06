package com.github.gabert.arachna.trace.renderer;

import com.github.gabert.arachna.trace.recorder.record.ArgumentsExitRecord;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsRecord;
import com.github.gabert.arachna.trace.recorder.record.ExceptionRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodEndRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodStartRecord;
import com.github.gabert.arachna.trace.recorder.record.RecordReader;
import com.github.gabert.arachna.trace.recorder.record.ReturnRecord;
import com.github.gabert.arachna.trace.recorder.record.SequenceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import com.github.gabert.arachna.trace.recorder.record.VersionRecord;

import java.util.ArrayList;
import java.util.List;
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
            "VR", "MS", "SI", "TN", "RI", "TS", "CL", "TI", "AR", "AX", "RT", "RE", "TE",
            "CI", "PI", "SQ");

    private RecordRenderer() {}

    public record Result(String threadName, List<String> lines) {}

    public static Result render(byte[] data) {
        return render(data, ALL_TAGS);
    }

    public static Result render(byte[] data, Set<String> emitTags) {
        return render(RecordReader.readAll(data), emitTags);
    }

    public static Result render(List<TraceRecord> records) {
        return render(records, ALL_TAGS);
    }

    public static Result render(List<TraceRecord> records, Set<String> emitTags) {
        List<String> lines = new ArrayList<>();
        String threadName = null;

        for (TraceRecord record : records) {
            for (TagEntry entry : tagsFor(record)) {
                if (entry.threadName() != null) {
                    threadName = entry.threadName();
                }
                if ("MS".equals(entry.tag()) || "VR".equals(entry.tag())
                        || emitTags.contains(entry.tag())) {
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
            return List.of(tag("TI", PayloadDecoder.toJson(t.cbor())));
        }
        if (record instanceof ThisInstanceRefRecord t) {
            return List.of(tag("TI", String.valueOf(t.objectId())));
        }
        if (record instanceof ArgumentsRecord a) {
            return List.of(tag("AR", PayloadDecoder.argumentsToJson(a.cbor())));
        }
        if (record instanceof ArgumentsExitRecord a) {
            return List.of(tag("AX", PayloadDecoder.argumentsToJson(a.cbor())));
        }
        if (record instanceof ReturnRecord r) {
            return r.isVoid()
                    ? List.of(tag("RT", "VOID"))
                    : List.of(tag("RT", "VALUE"), tag("RE", PayloadDecoder.toJson(r.cbor())));
        }
        if (record instanceof ExceptionRecord e) {
            return List.of(tag("RT", "EXCEPTION"), tag("RE", PayloadDecoder.toJson(e.cbor())));
        }
        if (record instanceof SequenceRecord s) {
            // Self-contained: value is `<callId>|<seq>` so the parser can
            // route by callId without re-using the CI tag (which already
            // carries dual meaning on MS / ME). Robust against multi-thread
            // interleaving where SQ may not be adjacent to its MS.
            String value = (s.callId() != null ? s.callId().toString() : "") + "|" + s.seq();
            return List.of(tag("SQ", value));
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
        if (m.callId()      != null) entries.add(tag("CI", m.callId().toString()));
        if (m.parentCallId() != null) entries.add(tag("PI", m.parentCallId().toString()));
        entries.add(threadName(m.threadName()));
        return entries;
    }

    private static List<TagEntry> tagsForMethodEnd(MethodEndRecord m) {
        List<TagEntry> entries = new ArrayList<>();
        entries.add(tag("TE", String.valueOf(m.timestamp())));
        entries.add(tag("TN", m.threadName()));
        entries.add(tag("RI", String.valueOf(m.requestId())));
        if (m.callId() != null) entries.add(tag("CI", m.callId().toString()));
        entries.add(threadName(m.threadName()));
        return entries;
    }

    // --- TagEntry ---

    private record TagEntry(String tag, String value, String threadName) {}

    private static TagEntry tag(String tag, String value) {
        return new TagEntry(tag, value, null);
    }

    private static TagEntry threadName(String name) {
        return new TagEntry("_threadName", "", name);
    }

}
