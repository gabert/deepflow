package com.github.gabert.deepflow.processor;

/**
 * One method invocation flattened from the line stream produced by
 * {@link com.github.gabert.deepflow.recorder.destination.RecordRenderer}.
 *
 * <p>Both {@code thisIdRef} and {@code thisJson} can be null:
 * static methods have no {@code TI} record; instance methods produce either
 * {@code thisIdRef} (when {@code expand_this=false}) or {@code thisJson}
 * (when {@code expand_this=true}). Likewise {@code argsExitJson} and
 * {@code returnJson} are null when the corresponding records are absent.</p>
 */
public record ParsedCall(
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
        String returnJson
) {

    public Long effectiveThisId() {
        if (thisIdRef != null) return thisIdRef;
        // When TI is full JSON, the id is in the root __meta__.id but extracting
        // here would require a JSON parse. The sink already parses for object_ids
        // collection, so it is responsible for filling this in if needed.
        return null;
    }
}
