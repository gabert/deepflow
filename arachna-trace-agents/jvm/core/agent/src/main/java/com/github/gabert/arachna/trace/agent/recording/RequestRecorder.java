package com.github.gabert.arachna.trace.agent.recording;

import com.github.gabert.arachna.trace.agent.AgentConfig;
import com.github.gabert.arachna.trace.agent.bootstrap.RequestContext;
import com.github.gabert.arachna.trace.agent.spi.SpiBootstrap;
import com.github.gabert.arachna.trace.codec.envelope.ObjectIdRegistry;
import com.github.gabert.arachna.trace.recorder.buffer.RecordBuffer;
import com.github.gabert.arachna.trace.recorder.record.BinaryUtil;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Owns the per-call recording logic: builds entry/exit byte records and
 * pushes them to the buffer. Constructed once at agent startup; the active
 * instance is held by {@code ArachnaTraceAdvice.RECORDER} and read by inlined
 * advice on every traced method invocation.
 *
 * <p>Flag fields (expandThis, serializeValues, emit*) are snapshotted from
 * the config in the constructor so the hot path does not pay a HashMap
 * lookup per call.</p>
 */
public class RequestRecorder {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private final RecordBuffer recordBuffer;
    private final ValueEncoder valueEncoder;
    private final SpiBootstrap spi;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final boolean emitTi;
    private final boolean emitAr;
    private final boolean emitReturnRecord;
    private final boolean emitAx;
    private final boolean emitSq;

    /**
     * Per-agent-run sequence counter. Incremented on each successful method
     * entry, regardless of thread or request — i.e. it reflects the order in
     * which the agent <em>observed</em> traced events. Carried on the wire by
     * {@code SequenceRecord} when {@code emit_tags} includes {@code SQ}, and
     * is the canonical ordering primitive for downstream consumers (sub-ms
     * ties on {@code ts_in} are disambiguated by this).
     */
    private final AtomicLong seqCounter = new AtomicLong(0);

    public RequestRecorder(RecordBuffer recordBuffer, AgentConfig config) {
        this.recordBuffer = recordBuffer;
        this.valueEncoder = new ValueEncoder(config.getMaxValueSize());
        this.spi = new SpiBootstrap(config);
        this.expandThis = config.isExpandThis();
        this.serializeValues = config.isSerializeValues();
        this.emitTi = config.shouldEmit("TI");
        this.emitAr = config.shouldEmit("AR");
        // RT and RE are written as a single byte-record (RT is the record-type
        // byte, RE is the optional payload). The renderer trims to whichever
        // tags are configured, so we only need to know whether either is wanted.
        // Exceptions ignore this flag entirely — see recordExit.
        this.emitReturnRecord = config.shouldEmit("RT") || config.shouldEmit("RE");
        this.emitAx = config.shouldEmit("AX");
        this.emitSq = config.shouldEmit("SQ");
        // Single decision point — the resolver itself short-circuits to
        // positional integer keys without touching its cache when this
        // flag is false. The recorder doesn't branch per-call.
        ParameterNamesResolver.setEnabled(config.isParameterNames());
    }

    public RecordBuffer getRecordBuffer() {
        return recordBuffer;
    }

    // --- Record entry ---

    /**
     * Records a method entry. Returns {@code true} iff the entry was fully
     * committed (the call's UUID has been pushed onto {@link RequestContext#CALL_STACK}
     * <em>and</em> the {@code MS} record has been queued). Callers (the
     * ByteBuddy advice in {@code ArachnaTraceAdvice}) MUST call
     * {@link #recordExit} <em>only</em> when this method returns {@code true}.
     *
     * <p>This contract makes the agent bulletproof against partial-record
     * cascades: a failure during entry leaves the stack and depth in the
     * exact pre-entry state, and the matching exit is suppressed — so no
     * subsequent call ever pairs against a wrong UUID. Worst case is a
     * dropped (silently ignored) call; never a wrong one.</p>
     */
    public boolean recordEntry(Method method, Object self, Object[] allArguments) {
        if (recordBuffer == null) return false;
        spi.initJpaProxyResolverOnce();

        UUID callId;
        byte[] record;
        boolean depthIncremented = false;
        try {
            String signature = MethodSignatureFormatter.format(method);
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();
            // Stack at this point: [recordEntry, target_method, caller_of_target, ...]
            // ByteBuddy inlines onEnter into target_method's bytecode, so the call
            // to recordEntry lives there at runtime. skip(2) walks past both frames
            // and lands on the actual caller of the traced method.
            int callerLine = STACK_WALKER
                    .walk(s -> s.skip(2).findFirst())
                    .map(StackWalker.StackFrame::getLineNumber)
                    .orElse(0);

            String sessionId = spi.getSessionIdResolver().resolve();

            long requestId = RequestContext.beginRequest();
            depthIncremented = true;

            UUID parentCallId = RequestContext.peekParentCallId();
            callId = UUID.randomUUID();

            byte[] sequenceRecord = emitSq
                    ? RecordWriter.sequence(callId, seqCounter.getAndIncrement())
                    : null;

            if (serializeValues) {
                Object selfForCapture = emitTi ? self : null;
                Object[] argsForCapture = emitAr ? allArguments : null;
                record = buildSerializedEntry(method, sessionId, signature, threadName, timestamp, callerLine,
                        requestId, callId, parentCallId, sequenceRecord, selfForCapture, argsForCapture);
            } else {
                byte[] startRecord = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp,
                        callerLine, requestId, callId, parentCallId);
                record = sequenceRecord != null
                        ? BinaryUtil.concat(startRecord, sequenceRecord)
                        : startRecord;
            }
        } catch (Throwable t) {
            // Roll back depth so the next root entry on this thread still
            // generates a fresh request id at depth==0. CALL_STACK is
            // untouched (push has not happened yet), so nothing to roll back
            // there.
            if (depthIncremented) {
                RequestContext.endRequest();
            }
            System.err.println("[ArachnaTrace] Error recording entry.");
            t.printStackTrace();
            return false;
        }

        // From here on, both operations are infallible — Deque.push() and
        // RecordBuffer.offer() do not throw. So once we get past the try
        // block above, the contract (push-and-emit happen together) is
        // guaranteed.
        RequestContext.pushCallId(callId);
        recordBuffer.offer(record);
        return true;
    }

    // --- Record exit ---

    public void recordExit(Method method, Object returned, Throwable throwable,
                           Object[] allArguments) {
        if (recordBuffer == null) return;
        // Pop FIRST and bail if empty: the bulletproof contract guarantees
        // recordExit is only called after a successful recordEntry pushed,
        // but a contract violation (e.g. advice misfire) would otherwise
        // emit a wrong-id ME and double-decrement depth. Abort before any
        // state mutation so we degrade silently rather than corrupting.
        UUID callId = RequestContext.popCallId();
        if (callId == null) return;
        long requestId = RequestContext.endRequest();
        try {
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();

            String sessionId = spi.getSessionIdResolver().resolve();

            byte[] record;
            if (serializeValues) {
                byte[] exitArgsCbor = (emitAx && emitAr && allArguments != null)
                        ? valueEncoder.encode(namedArgs(method, allArguments))
                        : null;

                byte[] returnRecord;
                if (throwable != null) {
                    // Exceptions are recorded regardless of emit_tags: RT is the
                    // structural source of is_exception downstream, and filtering
                    // a display tag must not turn an exceptional exit into VOID.
                    returnRecord = RecordWriter.exception(valueEncoder.encode(buildExceptionData(throwable)));
                } else if (!emitReturnRecord) {
                    returnRecord = RecordWriter.returnVoid();
                } else {
                    boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                    returnRecord = isVoid
                            ? RecordWriter.returnVoid()
                            : RecordWriter.returnValue(valueEncoder.encode(returned));
                }

                byte[] endRecord = RecordWriter.methodEnd(sessionId, threadName, timestamp, requestId, callId);
                byte[] exitArgsRecord = exitArgsCbor != null
                        ? RecordWriter.argumentsExit(exitArgsCbor)
                        : new byte[0];

                record = BinaryUtil.concat(endRecord, returnRecord, exitArgsRecord);
            } else {
                record = RecordWriter.methodEnd(sessionId, threadName, timestamp, requestId, callId);
            }
            recordBuffer.offer(record);
        } catch (Throwable t) {
            System.err.println("[ArachnaTrace] Error recording exit.");
            t.printStackTrace();
        }
    }

    // --- Private: entry record building ---

    private byte[] buildSerializedEntry(Method method,
                                         String sessionId, String signature, String threadName,
                                         long timestamp, int callerLine,
                                         long requestId,
                                         UUID callId, UUID parentCallId,
                                         byte[] sequenceRecord,
                                         Object self, Object[] allArguments) throws IOException {
        byte[] startRecord = RecordWriter.logEntrySimple(sessionId, signature, threadName, timestamp, callerLine,
                requestId, callId, parentCallId);

        byte[] thisRecord = null;
        if (self != null) {
            if (expandThis) {
                thisRecord = RecordWriter.thisInstance(valueEncoder.encode(self));
            } else {
                thisRecord = RecordWriter.thisInstanceRef(ObjectIdRegistry.idOf(self));
            }
        }

        byte[] argsRecord = null;
        if (allArguments != null) {
            argsRecord = RecordWriter.arguments(valueEncoder.encode(namedArgs(method, allArguments)));
        }

        return BinaryUtil.concat(startRecord, sequenceRecord, thisRecord, argsRecord);
    }

    /**
     * Wraps an {@code Object[]} of arguments into a key-preserving
     * {@link LinkedHashMap}. Keys come from {@link ParameterNamesResolver}
     * — strings when real names are available, integers when not — and
     * the recorder is agnostic to which: AR/AX is always a CBOR map
     * downstream regardless of source. Order matches declaration order.
     */
    private static Map<Object, Object> namedArgs(Method method, Object[] allArguments) {
        Object[] keys = ParameterNamesResolver.resolve(method);
        Map<Object, Object> map = new LinkedHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], i < allArguments.length ? allArguments[i] : null);
        }
        return map;
    }

    private static Map<String, Object> buildExceptionData(Throwable throwable) {
        List<String> stacktrace = Stream.of(throwable.getStackTrace())
                .map(StackTraceElement::toString)
                .toList();
        return Map.of(
                "message", String.valueOf(throwable.getMessage()),
                "stacktrace", stacktrace
        );
    }

}
