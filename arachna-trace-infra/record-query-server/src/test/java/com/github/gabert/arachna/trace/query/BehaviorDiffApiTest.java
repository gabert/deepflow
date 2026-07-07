package com.github.gabert.arachna.trace.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorDiffApiTest {

    private static Map<String, Object> row(String callId, String sig, String ar, String re) {
        return Map.of("call_id", callId, "signature", sig, "ar_hash", ar, "re_hash", re, "is_exception", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifiesAddedRemovedChangedUnchanged() {
        List<Map<String, Object>> a = List.of(
                row("a1", "Svc.same(int)",    "h-in-1", "h-out-1"),   // unchanged
                row("a2", "Svc.changed(int)", "h-in-2", "h-out-old"), // output_changed
                row("a3", "Svc.removed()",    "h-in-3", "h-out-3")    // removed
        );
        List<Map<String, Object>> b = List.of(
                row("b1", "Svc.same(int)",    "h-in-1", "h-out-1"),
                row("b2", "Svc.changed(int)", "h-in-2", "h-out-new"),
                row("b4", "Svc.added()",      "h-in-4", "h-out-4")    // added
        );

        Map<String, Object> result = BehaviorDiffApi.computeDiff(a, b);
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("output_changed"));
        assertEquals(1, summary.get("added"));
        assertEquals(1, summary.get("removed"));
        assertEquals(1, summary.get("unchanged"));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        // output_changed sorts first; it must carry example calls from both sides.
        Map<String, Object> changed = groups.get(0);
        assertEquals("output_changed", changed.get("status"));
        assertEquals("Svc.changed(int)", changed.get("signature"));
        assertEquals("a2", changed.get("example_call_a"));
        assertEquals("b2", changed.get("example_call_b"));
        assertEquals(List.of("h-out-old"), changed.get("re_hashes_a"));
        assertEquals(List.of("h-out-new"), changed.get("re_hashes_b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameInputCalledTwiceWithStableOutputIsUnchanged() {
        // Repeated invocations with identical (input, output) collapse into
        // one group — call counts differ across sides without being flagged.
        List<Map<String, Object>> a = List.of(
                row("a1", "Svc.f(int)", "h-in", "h-out"),
                row("a2", "Svc.f(int)", "h-in", "h-out"));
        List<Map<String, Object>> b = List.of(
                row("b1", "Svc.f(int)", "h-in", "h-out"));

        Map<String, Object> result = BehaviorDiffApi.computeDiff(a, b);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        assertEquals(1, groups.size());
        assertEquals("unchanged", groups.get(0).get("status"));
        assertEquals(2, groups.get(0).get("count_a"));
        assertEquals(1, groups.get(0).get("count_b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonDeterministicOutputWithinOneSideStillComparesAsSet() {
        // If one side saw two distinct outputs for the same input and the
        // other saw the same two, the *set* comparison says unchanged —
        // flagging it would blame nondeterminism on the code change.
        List<Map<String, Object>> a = List.of(
                row("a1", "Svc.f()", "h", "out-x"),
                row("a2", "Svc.f()", "h", "out-y"));
        List<Map<String, Object>> b = List.of(
                row("b1", "Svc.f()", "h", "out-y"),
                row("b2", "Svc.f()", "h", "out-x"));

        Map<String, Object> result = BehaviorDiffApi.computeDiff(a, b);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        assertEquals("unchanged", groups.get(0).get("status"));
    }

    @Test
    void previewStripsMetaAndTruncates() {
        String payload = """
                {"__meta__":{"id":1,"class":"X","hash":"h"},
                 "book":{"__meta__":{"id":2},"title":"War with the Newts","year":1936},
                 "quantity":3}""";
        String preview = BehaviorDiffApi.previewOf(payload);
        assertEquals("{\"book\":{\"title\":\"War with the Newts\",\"year\":1936},\"quantity\":3}", preview);

        String big = "{\"text\":\"" + "x".repeat(500) + "\"}";
        assertTrue(BehaviorDiffApi.previewOf(big).length() <= 160);
        assertTrue(BehaviorDiffApi.previewOf(big).endsWith("…"));

        assertEquals("", BehaviorDiffApi.previewOf("not json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadLessCallsParticipateInFlowComparison() {
        // Structural-mode rows (no AR/RE hashes) still show flow changes.
        List<Map<String, Object>> a = List.of(row("a1", "Svc.old()", "", ""));
        List<Map<String, Object>> b = List.of(row("b1", "Svc.new()", "", ""));

        Map<String, Object> result = BehaviorDiffApi.computeDiff(a, b);
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("added"));
        assertEquals(1, summary.get("removed"));
        assertEquals(0, summary.get("output_changed"));
    }
}
