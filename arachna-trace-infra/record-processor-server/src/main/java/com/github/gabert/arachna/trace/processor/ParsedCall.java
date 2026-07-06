package com.github.gabert.arachna.trace.processor;

import java.util.UUID;

/**
 * One method invocation flattened from the typed
 * {@link com.github.gabert.arachna.trace.recorder.record.TraceRecord} stream
 * by {@link RecordParser}. Payload JSON fields are already hash-enriched.
 *
 * <p>Both {@code thisIdRef} and {@code thisJson} can be null:
 * static methods have no {@code TI} record; instance methods produce either
 * {@code thisIdRef} (when {@code expand_this=false}) or {@code thisJson}
 * (when {@code expand_this=true}). Likewise {@code argsExitJson} and
 * {@code returnJson} are null when the corresponding records are absent.</p>
 *
 * <p>{@code callId} identifies the call uniquely within a JVM run;
 * {@code parentCallId} is the call that contains this one (lexically for
 * sync calls, by submitter for async-propagated calls), and is null at the
 * root of a request. Agent-run identity is carried at the transport layer
 * — see {@link com.github.gabert.arachna.trace.codec.AgentRun}.</p>
 */
public record ParsedCall(
        UUID callId,
        UUID parentCallId,
        String sessionId,
        long requestId,
        String threadName,
        long tsInMillis,
        long tsOutMillis,
        String signature,
        int callerLine,
        String returnType,
        Long thisIdRef,
        String thisJson,
        String argsJson,
        String argsExitJson,
        String returnJson,
        long seq
) {

    public Long effectiveThisId() {
        if (thisIdRef != null) return thisIdRef;
        // When TI is full JSON, the id is in the root __meta__.id but extracting
        // here would require a JSON parse. The sink already parses for object_ids
        // collection, so it is responsible for filling this in if needed.
        return null;
    }
}
