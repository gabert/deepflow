package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.recorder.record.ArgumentsExitRecord;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsRecord;
import com.github.gabert.arachna.trace.recorder.record.ExceptionRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodEndRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodStartRecord;
import com.github.gabert.arachna.trace.recorder.record.ReturnRecord;
import com.github.gabert.arachna.trace.recorder.record.SequenceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import com.github.gabert.arachna.trace.renderer.PayloadDecoder;
import com.github.gabert.arachna.trace.renderer.RecordHashEnricher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Walks a typed {@link TraceRecord} stream and pairs up
 * {@link MethodStartRecord}/{@link MethodEndRecord} via the wire-carried
 * {@code call_id} UUID to emit one {@link ParsedCall} per method invocation.
 * Value payloads (TI/AR/AX/RE) are decoded from CBOR to readable JSON via
 * {@link PayloadDecoder} and hash-enriched via {@link RecordHashEnricher}
 * as they are attached.
 *
 * <h2>Why stateful and UUID-keyed (vs a per-batch stack)</h2>
 *
 * <p>A stack-based pairer had a latent bug: a request whose root MS arrived
 * in poll N and root ME in poll N+1 was silently dropped, and multi-thread
 * interleaving in one batch pretended to share one stack. Open calls are
 * instead keyed by their UUID in {@link #openCalls}; state persists across
 * {@code parse()} calls, so an ME finds its MS no matter which batch each
 * lived in, and every call is uniquely addressable by id.</p>
 *
 * <h2>Wire ordering this code handles</h2>
 *
 * <p>Record frames for one logical event are contiguous in the byte stream
 * (the agent enqueues each entry/exit as one concatenated blob):
 * <pre>
 *   entry -> METHOD_START, [SEQUENCE], [THIS_INSTANCE|THIS_INSTANCE_REF], [ARGUMENTS]
 *   exit  -> METHOD_END, RETURN|EXCEPTION, [ARGUMENTS_EXIT]
 * </pre>
 * So the parser keeps an "entry context" (the last MS's builder, receiving
 * TI/AR) and an "exit context" (the last ME's builder, receiving RT/RE/AX)
 * until the next MS or ME arrives.</p>
 *
 * <h2>Lifecycle and TTL eviction</h2>
 *
 * <p>One instance per {@code ClickHouseSink} (or any owner). Open calls live
 * in {@link #openCalls} until their matching ME or until TTL eviction reaps
 * them (an MS without an ME means the agent crashed mid-call; the entry would
 * otherwise leak forever). The sweep runs at the end of {@code parse()} and
 * is throttled to {@link #SWEEP_INTERVAL_MS}. The clock is injectable for
 * tests. Both knobs are deliberately hard-coded — the TTL is loose enough
 * (10 minutes) to be safely above any plausible real-world method duration.</p>
 */
public final class RecordParser {

    /** Drop open-call entries whose processor-side admission age exceeds this. */
    private static final long OPEN_CALL_TTL_MS = 10 * 60 * 1000L;
    /** Skip sweeping more often than this — the eviction itself is O(n). */
    private static final long SWEEP_INTERVAL_MS = 60 * 1000L;

    private final Map<UUID, Builder> openCalls = new HashMap<>();
    private final LongSupplier clock;
    private long nextSweepAt;

    /** Builder currently accumulating payloads from an MS record (entry context). */
    private Builder currentEntry;

    /** Builder accumulating RT/RE/AX from an ME record (exit context). */
    private Builder currentExit;

    public RecordParser() {
        this(System::currentTimeMillis);
    }

    /** Test-only: inject a clock so eviction can be exercised deterministically. */
    RecordParser(LongSupplier clock) {
        this.clock = clock;
    }

    public List<ParsedCall> parse(List<TraceRecord> records) {
        List<ParsedCall> completed = new ArrayList<>();

        for (TraceRecord record : records) {
            if (record instanceof MethodStartRecord ms) {
                onMethodStart(ms, completed);
            } else if (record instanceof MethodEndRecord me) {
                onMethodEnd(me, completed);
            } else if (record instanceof SequenceRecord sq) {
                onSequence(sq);
            } else if (record instanceof ThisInstanceRecord ti) {
                if (currentEntry != null) {
                    currentEntry.thisJson = enrich(PayloadDecoder.toJson(ti.cbor()));
                }
            } else if (record instanceof ThisInstanceRefRecord ref) {
                if (currentEntry != null) {
                    currentEntry.thisIdRef = ref.objectId();
                }
            } else if (record instanceof ArgumentsRecord ar) {
                if (currentEntry != null) {
                    currentEntry.argsJson = enrich(PayloadDecoder.argumentsToJson(ar.cbor()));
                }
            } else if (record instanceof ArgumentsExitRecord ax) {
                if (currentExit != null) {
                    currentExit.argsExitJson = enrich(PayloadDecoder.argumentsToJson(ax.cbor()));
                }
            } else if (record instanceof ReturnRecord rt) {
                if (currentExit != null) {
                    if (rt.isVoid()) {
                        currentExit.returnType = "VOID";
                    } else {
                        currentExit.returnType = "VALUE";
                        currentExit.returnJson = enrich(PayloadDecoder.toJson(rt.cbor()));
                    }
                }
            } else if (record instanceof ExceptionRecord ex) {
                if (currentExit != null) {
                    currentExit.returnType = "EXCEPTION";
                    currentExit.returnJson = enrich(PayloadDecoder.toJson(ex.cbor()));
                }
            }
            // VersionRecord: wire-format banner — not modeled.
        }

        // End of batch: flush any in-progress exit. An open entry stays in
        // openCalls for the next batch — its ME may arrive later.
        flushExitIfAny(completed);
        currentEntry = null;
        currentExit = null;

        evictStaleOpenCalls();

        return completed;
    }

    private void onMethodStart(MethodStartRecord ms, List<ParsedCall> completed) {
        flushExitIfAny(completed);
        Builder b = new Builder(clock.getAsLong());
        b.callId = ms.callId();
        b.parentCallId = ms.parentCallId();
        b.sessionId = ms.sessionId();
        b.requestId = ms.requestId();
        b.threadName = ms.threadName();
        b.tsIn = ms.timestamp();
        b.signature = ms.signature();
        b.callerLine = ms.callerLine();
        currentEntry = b;
        if (b.callId != null) {
            openCalls.put(b.callId, b);
        }
        // A callId-less MS (agent misbehavior) can never be paired with its
        // ME — it lives only as the current entry context and is discarded
        // at the next MS/ME.
    }

    private void onMethodEnd(MethodEndRecord me, List<ParsedCall> completed) {
        flushExitIfAny(completed);
        currentEntry = null;
        Builder b = me.callId() != null ? openCalls.remove(me.callId()) : null;
        if (b != null) {
            b.tsOut = me.timestamp();
            currentExit = b;
        }
        // Else orphan ME — entry never recorded (failed-entry contract in
        // RequestRecorder) or already TTL-evicted. Nothing to pair.
    }

    private void onSequence(SequenceRecord sq) {
        if (sq.callId() == null) return;
        Builder b = openCalls.get(sq.callId());
        if (b != null) {
            b.seq = sq.seq();
        }
        // SQ is emitted inside the entry blob, so its call is always still
        // open; an SQ for an unknown call is a stray and is dropped.
    }

    private void flushExitIfAny(List<ParsedCall> completed) {
        if (currentExit != null) {
            completed.add(currentExit.build());
            currentExit = null;
        }
    }

    private void evictStaleOpenCalls() {
        long now = clock.getAsLong();
        if (now < nextSweepAt) return;
        nextSweepAt = now + SWEEP_INTERVAL_MS;
        long cutoff = now - OPEN_CALL_TTL_MS;
        openCalls.entrySet().removeIf(e -> e.getValue().openedAtMillis < cutoff);
    }

    /** Test-only: visibility on retained state for eviction assertions. */
    int openCallCount() {
        return openCalls.size();
    }

    private static String enrich(String json) {
        return RecordHashEnricher.enrichJson(json);
    }

    private static final class Builder {
        final long openedAtMillis;
        UUID callId;
        UUID parentCallId;
        String sessionId;
        long requestId;
        String threadName;
        long tsIn;
        long tsOut;
        String signature;
        int callerLine;
        String returnType = "VOID";
        Long thisIdRef;
        String thisJson;
        String argsJson;
        String argsExitJson;
        String returnJson;
        long seq;

        Builder(long openedAtMillis) {
            this.openedAtMillis = openedAtMillis;
        }

        ParsedCall build() {
            return new ParsedCall(
                    callId, parentCallId,
                    sessionId, requestId, threadName,
                    tsIn, tsOut, signature, callerLine,
                    returnType, thisIdRef, thisJson,
                    argsJson, argsExitJson, returnJson, seq);
        }
    }
}
