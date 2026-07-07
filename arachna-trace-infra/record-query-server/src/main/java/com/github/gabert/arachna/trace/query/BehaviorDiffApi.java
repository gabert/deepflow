package com.github.gabert.arachna.trace.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Behavioral diff between two recorded sessions — the read side of the
 * "review the change, not the diff" workflow for AI-generated code.
 *
 * <p>Two runs of the same scenario (e.g. main vs an agent-authored
 * branch, distinguishable by {@code agent_runs.code_version}) produce two
 * sessions. Every call carries the Merkle content hash of its arguments
 * ({@code AR root_hash}) and of its return value ({@code RE root_hash}),
 * so behaviour can be compared without reading payloads: group calls by
 * {@code (signature, ar_hash)} — "this method invoked with exactly this
 * input" — and compare the set of output hashes each side produced.
 * A group present on one side only is flow added/removed; a group present
 * on both with different output hashes is the sharp signal: <em>same
 * input, different output</em>.</p>
 *
 * <p>The hash comparison is a column equality in ClickHouse terms — no
 * payload JSON leaves the database until the user drills into a specific
 * group (via the example call ids each row carries).</p>
 */
class BehaviorDiffApi {

    private final ClickHouseClient ch;

    BehaviorDiffApi(ClickHouseClient ch) {
        this.ch = ch;
    }

    /**
     * {@code GET /api/analysis/behavior-diff?session_a=...&session_b=...}
     *
     * <p>Response: {@code { summary: {...}, groups: [ { signature, ar_hash,
     * status, count_a, count_b, re_hashes_a, re_hashes_b, example_call_a,
     * example_call_b } ] }} where {@code status} is one of
     * {@code added | removed | output_changed | unchanged}. Groups are
     * sorted: output_changed first, then added, removed, unchanged —
     * each alphabetically by signature.</p>
     */
    Map<String, Object> behaviorDiff(Map<String, List<String>> params) throws Exception {
        String sessionA = Params.required(params, "session_a");
        String sessionB = Params.required(params, "session_b");
        List<Map<String, Object>> rowsA = callHashes(sessionA);
        List<Map<String, Object>> rowsB = callHashes(sessionB);
        Map<String, Object> result = computeDiff(rowsA, rowsB);
        attachInputPreviews(result);
        return result;
    }

    /**
     * Groups are keyed by {@code (signature, ar_hash)}, so a method invoked
     * with several distinct inputs produces several visually identical rows.
     * For the changed rows (the ones a reviewer reads — capped, unchanged
     * rows skipped) this fetches the example call's AR payload and attaches
     * a compact, {@code __meta__}-stripped preview so each row says which
     * input it is about.
     */
    private static final int PREVIEW_GROUP_CAP = 200;
    private static final int PREVIEW_MAX_CHARS = 160;

    @SuppressWarnings("unchecked")
    private void attachInputPreviews(Map<String, Object> result) throws Exception {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        List<Map<String, Object>> wanted = groups.stream()
                .filter(g -> !"unchanged".equals(g.get("status")))
                .limit(PREVIEW_GROUP_CAP)
                .toList();
        if (wanted.isEmpty()) return;

        List<String> callIds = wanted.stream()
                .map(g -> g.get("example_call_a") != null ? g.get("example_call_a") : g.get("example_call_b"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toList();

        String inList = callIds.stream()
                .map(id -> "{c" + callIds.indexOf(id) + ":UUID}")
                .reduce((x, y) -> x + "," + y).orElse("");
        Map<String, String> bind = new LinkedHashMap<>();
        for (int i = 0; i < callIds.size(); i++) {
            java.util.UUID.fromString(callIds.get(i));
            bind.put("c" + i, callIds.get(i));
        }
        List<Map<String, Object>> payloadRows = ch.query("""
                SELECT call_id, payload_json
                FROM payloads
                WHERE kind = 'AR' AND call_id IN (""" + inList + ")", bind);

        Map<String, String> previewByCall = new LinkedHashMap<>();
        for (Map<String, Object> row : payloadRows) {
            previewByCall.put(String.valueOf(row.get("call_id")),
                    previewOf(String.valueOf(row.get("payload_json"))));
        }
        for (Map<String, Object> g : wanted) {
            Object exampleId = g.get("example_call_a") != null ? g.get("example_call_a") : g.get("example_call_b");
            g.put("input_preview", exampleId == null ? "" : previewByCall.getOrDefault(String.valueOf(exampleId), ""));
        }
    }

    /** Compact one-line rendering of an AR payload without __meta__ noise. */
    static String previewOf(String payloadJson) {
        try {
            Object parsed = PREVIEW_MAPPER.readValue(payloadJson, Object.class);
            Object stripped = stripMeta(parsed);
            String compact = PREVIEW_MAPPER.writeValueAsString(stripped);
            return compact.length() > PREVIEW_MAX_CHARS
                    ? compact.substring(0, PREVIEW_MAX_CHARS - 1) + "…"
                    : compact;
        } catch (Exception e) {
            return "";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper PREVIEW_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static Object stripMeta(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(BehaviorDiffApi::stripMeta).toList();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("__meta__".equals(entry.getKey())) continue;
                out.put(String.valueOf(entry.getKey()), stripMeta(entry.getValue()));
            }
            return out;
        }
        return node;
    }

    /**
     * {@code GET /api/analysis/observed-signatures?session_id=...}
     *
     * <p>Distinct method signatures observed in a session, with call
     * counts. One half of the liveness-sweep workflow: diff this list
     * against the agent's instrumentation inventory
     * ({@code instrumentation_inventory} agent config) to find methods
     * that are instrumented but never executed under the exercised
     * traffic.</p>
     */
    List<Map<String, Object>> observedSignatures(Map<String, List<String>> params) throws Exception {
        String sessionId = Params.required(params, "session_id");
        return ch.query("""
                SELECT signature, count() AS call_count
                FROM calls
                WHERE session_id = {session_id:String}
                GROUP BY signature
                ORDER BY signature
                """, Map.of("session_id", sessionId));
    }

    // --- Data access ---

    /**
     * One row per call: signature plus the AR/RE root hashes (empty
     * string when the kind wasn't captured — e.g. void returns or
     * structural mode). Based on {@code calls} so payload-less calls
     * still participate in flow-level (added/removed) comparison.
     */
    private List<Map<String, Object>> callHashes(String sessionId) throws Exception {
        return ch.query("""
                SELECT c.call_id            AS call_id,
                       c.signature          AS signature,
                       c.is_exception       AS is_exception,
                       p.ar_hash            AS ar_hash,
                       p.re_hash            AS re_hash
                FROM calls c
                LEFT JOIN (
                    SELECT call_id,
                           anyIf(root_hash, kind = 'AR') AS ar_hash,
                           anyIf(root_hash, kind = 'RE') AS re_hash
                    FROM payloads
                    WHERE session_id = {session_id:String}
                    GROUP BY call_id
                ) p ON p.call_id = c.call_id
                WHERE c.session_id = {session_id:String}
                LIMIT 200000
                """, Map.of("session_id", sessionId));
    }

    // --- Pure diff core (unit-tested without ClickHouse) ---

    /**
     * Groups both sides by {@code (signature, ar_hash)} and classifies
     * each group. Pure function over the row lists returned by
     * {@link #callHashes}; package-visible for tests.
     */
    static Map<String, Object> computeDiff(List<Map<String, Object>> rowsA,
                                           List<Map<String, Object>> rowsB) {
        Map<String, Group> a = groupByInput(rowsA);
        Map<String, Group> b = groupByInput(rowsB);

        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());

        int added = 0, removed = 0, changed = 0, unchanged = 0;
        for (String key : keys) {
            Group ga = a.get(key);
            Group gb = b.get(key);
            String status;
            if (ga == null) {
                status = "added";
                added++;
            } else if (gb == null) {
                status = "removed";
                removed++;
            } else if (!ga.reHashes.equals(gb.reHashes)) {
                status = "output_changed";
                changed++;
            } else {
                status = "unchanged";
                unchanged++;
            }
            Group any = ga != null ? ga : gb;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("signature", any.signature);
            row.put("ar_hash", any.arHash);
            row.put("status", status);
            row.put("count_a", ga == null ? 0 : ga.count);
            row.put("count_b", gb == null ? 0 : gb.count);
            row.put("re_hashes_a", ga == null ? List.of() : new ArrayList<>(ga.reHashes));
            row.put("re_hashes_b", gb == null ? List.of() : new ArrayList<>(gb.reHashes));
            row.put("exception_a", ga != null && ga.exception);
            row.put("exception_b", gb != null && gb.exception);
            row.put("example_call_a", ga == null ? null : ga.exampleCallId);
            row.put("example_call_b", gb == null ? null : gb.exampleCallId);
            out.add(row);
        }

        out.sort((x, y) -> {
            int sx = statusRank((String) x.get("status"));
            int sy = statusRank((String) y.get("status"));
            if (sx != sy) return Integer.compare(sx, sy);
            return String.valueOf(x.get("signature")).compareTo(String.valueOf(y.get("signature")));
        });

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls_a", rowsA.size());
        summary.put("calls_b", rowsB.size());
        summary.put("groups", out.size());
        summary.put("output_changed", changed);
        summary.put("added", added);
        summary.put("removed", removed);
        summary.put("unchanged", unchanged);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("groups", out);
        return result;
    }

    private static Map<String, Group> groupByInput(List<Map<String, Object>> rows) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String signature = str(row.get("signature"));
            String arHash = str(row.get("ar_hash"));
            String key = signature + "|" + arHash;
            Group g = groups.computeIfAbsent(key, k -> new Group(signature, arHash));
            g.count++;
            g.reHashes.add(str(row.get("re_hash")));
            g.exception |= truthy(row.get("is_exception"));
            if (g.exampleCallId == null) {
                g.exampleCallId = str(row.get("call_id"));
            }
        }
        return groups;
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "output_changed" -> 0;
            case "added" -> 1;
            case "removed" -> 2;
            default -> 3;
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean bool) return bool;
        if (o instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(o)) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static final class Group {
        final String signature;
        final String arHash;
        int count;
        boolean exception;
        String exampleCallId;
        // Sorted so set equality is order-insensitive and the JSON
        // output is deterministic.
        final TreeSet<String> reHashes = new TreeSet<>();

        Group(String signature, String arHash) {
            this.signature = signature;
            this.arHash = arHash;
        }
    }
}
