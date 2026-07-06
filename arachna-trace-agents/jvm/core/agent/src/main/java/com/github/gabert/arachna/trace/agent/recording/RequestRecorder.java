package com.github.gabert.arachna.trace.agent.recording;

import com.github.gabert.arachna.trace.agent.AgentConfig;
import com.github.gabert.arachna.trace.agent.bootstrap.RequestContext;
import com.github.gabert.arachna.trace.agent.spi.SpiBootstrap;
import com.github.gabert.arachna.trace.codec.envelope.ObjectIdRegistry;
import com.github.gabert.arachna.trace.recorder.buffer.RecordBuffer;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsExitRecord;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsRecord;
import com.github.gabert.arachna.trace.recorder.record.ExceptionRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodEndRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodStartRecord;
import com.github.gabert.arachna.trace.recorder.record.RawFrame;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;
import com.github.gabert.arachna.trace.recorder.record.ReturnRecord;
import com.github.gabert.arachna.trace.recorder.record.SequenceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRecord;
import com.github.gabert.arachna.trace.recorder.record.ThisInstanceRefRecord;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * <p>The hot-path entry points identify the method by declaring class +
 * {@code name+descriptor} key (both constant-pool loads in the inlined
 * advice); everything derived from the method — signature bytes, AR/AX
 * keys, void-ness — comes from {@link MethodMetaCache} and is resolved once
 * per method, not per call. Thread-name and session-id UTF-8 encodings are
 * cached per thread (one small, thread-lifetime entry each). The
 * {@link Method}-typed overloads exist for direct (non-advice) callers such
 * as tests.</p>
 */
public class RequestRecorder {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Per-thread cache of the last-seen threadName/sessionId and their UTF-8
     * bytes. Both strings repeat across thousands of consecutive calls
     * (thread names are stable; session ids per request), so the encode +
     * allocation happens only when the value actually changes. Footprint is
     * two strings + two small arrays per thread, released with the thread.
     */
    private static final ThreadLocal<ThreadStringCache> STRING_CACHE =
            ThreadLocal.withInitial(ThreadStringCache::new);

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
    private final boolean emitCl;

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
        // CL requires a stack walk per entry — skip it entirely when the tag
        // is filtered out.
        this.emitCl = config.shouldEmit("CL");
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
     * committed (the call's UUID has been pushed onto the thread's call stack
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
    public boolean recordEntry(Class<?> declaringType, String methodKey, Object self, Object[] allArguments) {
        if (recordBuffer == null) return false;
        spi.initJpaProxyResolverOnce();

        RequestContext.State ctx = RequestContext.state();
        UUID callId;
        byte[] record;
        boolean depthIncremented = false;
        try {
            MethodMeta meta = MethodMetaCache.get(declaringType, methodKey);
            ThreadStringCache strings = STRING_CACHE.get();
            byte[] threadNameBytes = strings.threadName(Thread.currentThread().getName());
            long timestamp = System.currentTimeMillis();
            // Stack at this point: [recordEntry, target_method, caller_of_target, ...]
            // ByteBuddy inlines onEnter into target_method's bytecode, so the call
            // to recordEntry lives there at runtime. skip(2) walks past both frames
            // and lands on the actual caller of the traced method.
            int callerLine = emitCl
                    ? STACK_WALKER
                            .walk(s -> s.skip(2).findFirst())
                            .map(StackWalker.StackFrame::getLineNumber)
                            .orElse(0)
                    : 0;

            byte[] sessionIdBytes = strings.sessionId(spi.getSessionIdResolver().resolve());

            long requestId = ctx.beginRequest();
            depthIncremented = true;

            UUID parentCallId = ctx.peekParentCallId();
            callId = randomCallId();

            RawFrame startRecord = new RawFrame(MethodStartRecord.TYPE,
                    MethodStartRecord.payloadFrom(sessionIdBytes, meta.signatureUtf8(), threadNameBytes,
                            timestamp, callerLine, requestId, callId, parentCallId));
            SequenceRecord sequenceRecord = emitSq
                    ? new SequenceRecord(callId, seqCounter.getAndIncrement())
                    : null;

            if (serializeValues) {
                Object selfForCapture = emitTi ? self : null;
                Object[] argsForCapture = emitAr ? allArguments : null;
                record = buildSerializedEntry(meta, startRecord, sequenceRecord,
                        selfForCapture, argsForCapture);
            } else {
                record = RecordWriter.frames(startRecord, sequenceRecord);
            }
        } catch (Throwable t) {
            // Roll back depth so the next root entry on this thread still
            // generates a fresh request id at depth==0. The call stack is
            // untouched (push has not happened yet), so nothing to roll back
            // there.
            if (depthIncremented) {
                ctx.endRequest();
            }
            System.err.println("[ArachnaTrace] Error recording entry.");
            t.printStackTrace();
            return false;
        }

        // From here on, both operations are infallible — Deque.push() and
        // RecordBuffer.offer() do not throw. So once we get past the try
        // block above, the contract (push-and-emit happen together) is
        // guaranteed.
        ctx.pushCallId(callId);
        recordBuffer.offer(record);
        return true;
    }

    /** {@link Method}-typed convenience for direct (non-advice) callers. */
    public boolean recordEntry(Method method, Object self, Object[] allArguments) {
        return recordEntry(method.getDeclaringClass(), MethodMetaCache.keyOf(method), self, allArguments);
    }

    // --- Record exit ---

    public void recordExit(Class<?> declaringType, String methodKey, Object returned, Throwable throwable,
                           Object[] allArguments) {
        if (recordBuffer == null) return;
        // Pop FIRST and bail if empty: the bulletproof contract guarantees
        // recordExit is only called after a successful recordEntry pushed,
        // but a contract violation (e.g. advice misfire) would otherwise
        // emit a wrong-id ME and double-decrement depth. Abort before any
        // state mutation so we degrade silently rather than corrupting.
        RequestContext.State ctx = RequestContext.state();
        UUID callId = ctx.popCallId();
        if (callId == null) return;
        long requestId = ctx.endRequest();
        try {
            ThreadStringCache strings = STRING_CACHE.get();
            byte[] threadNameBytes = strings.threadName(Thread.currentThread().getName());
            long timestamp = System.currentTimeMillis();

            byte[] sessionIdBytes = strings.sessionId(spi.getSessionIdResolver().resolve());

            RawFrame endRecord = new RawFrame(MethodEndRecord.TYPE,
                    MethodEndRecord.payloadFrom(sessionIdBytes, threadNameBytes, timestamp,
                            requestId, callId));

            byte[] record;
            if (serializeValues) {
                MethodMeta meta = MethodMetaCache.get(declaringType, methodKey);
                ArgumentsExitRecord exitArgsRecord = (emitAx && emitAr && allArguments != null)
                        ? new ArgumentsExitRecord(valueEncoder.encode(namedArgs(meta.paramKeys(), allArguments)))
                        : null;

                TraceRecord returnRecord;
                if (throwable != null) {
                    // Exceptions are recorded regardless of emit_tags: RT is the
                    // structural source of is_exception downstream, and filtering
                    // a display tag must not turn an exceptional exit into VOID.
                    returnRecord = new ExceptionRecord(valueEncoder.encode(buildExceptionData(throwable)));
                } else if (!emitReturnRecord) {
                    returnRecord = ReturnRecord.ofVoid();
                } else {
                    returnRecord = meta.isVoidReturn()
                            ? ReturnRecord.ofVoid()
                            : new ReturnRecord(valueEncoder.encode(returned));
                }

                record = RecordWriter.frames(endRecord, returnRecord, exitArgsRecord);
            } else {
                record = RecordWriter.frames(endRecord);
            }
            recordBuffer.offer(record);
        } catch (Throwable t) {
            System.err.println("[ArachnaTrace] Error recording exit.");
            t.printStackTrace();
        }
    }

    /** {@link Method}-typed convenience for direct (non-advice) callers. */
    public void recordExit(Method method, Object returned, Throwable throwable, Object[] allArguments) {
        recordExit(method.getDeclaringClass(), MethodMetaCache.keyOf(method), returned, throwable, allArguments);
    }

    // --- Private: entry record building ---

    private byte[] buildSerializedEntry(MethodMeta meta, RawFrame startRecord,
                                         SequenceRecord sequenceRecord,
                                         Object self, Object[] allArguments) throws IOException {
        TraceRecord thisRecord = null;
        if (self != null) {
            if (expandThis) {
                thisRecord = new ThisInstanceRecord(valueEncoder.encode(self));
            } else {
                thisRecord = new ThisInstanceRefRecord(ObjectIdRegistry.idOf(self));
            }
        }

        ArgumentsRecord argsRecord = null;
        if (allArguments != null) {
            argsRecord = new ArgumentsRecord(valueEncoder.encode(namedArgs(meta.paramKeys(), allArguments)));
        }

        return RecordWriter.frames(startRecord, sequenceRecord, thisRecord, argsRecord);
    }

    /**
     * Random (v4) call ID from {@link ThreadLocalRandom}. Call IDs need
     * uniqueness, not unpredictability — the shared {@code SecureRandom}
     * behind {@code UUID.randomUUID()} is synchronized and becomes a
     * cross-thread contention point on the hot path. Version/variant bits
     * are set properly, so the all-zero "no UUID" wire sentinel can never
     * be produced.
     */
    private static UUID randomCallId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /**
     * Wraps an {@code Object[]} of arguments into a key-preserving
     * {@link LinkedHashMap}. Keys come from the method's cached
     * {@link MethodMeta} — strings when real names are available, integers
     * when not — and the recorder is agnostic to which: AR/AX is always a
     * CBOR map downstream regardless of source. Order matches declaration
     * order.
     */
    private static Map<Object, Object> namedArgs(Object[] keys, Object[] allArguments) {
        Map<Object, Object> map = new LinkedHashMap<>(keys.length * 4 / 3 + 1);
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

    private static final class ThreadStringCache {
        private String lastThreadName;
        private byte[] threadNameBytes;
        private String lastSessionId;
        private byte[] sessionIdBytes;

        byte[] threadName(String name) {
            // Thread.getName() returns the same String instance until
            // setName — reference check is the fast path.
            if (name != lastThreadName) {
                threadNameBytes = name.getBytes(StandardCharsets.UTF_8);
                lastThreadName = name;
            }
            return threadNameBytes;
        }

        byte[] sessionId(String sessionId) {
            if (sessionId == null) return EMPTY_BYTES;
            // Resolvers may return a fresh String per call — equals, with a
            // reference check inside String.equals covering the stable case.
            if (!sessionId.equals(lastSessionId)) {
                sessionIdBytes = sessionId.getBytes(StandardCharsets.UTF_8);
                lastSessionId = sessionId;
            }
            return sessionIdBytes;
        }
    }

}
