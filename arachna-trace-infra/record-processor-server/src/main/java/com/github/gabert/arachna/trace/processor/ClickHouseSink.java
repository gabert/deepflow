package com.github.gabert.arachna.trace.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.codec.Hasher;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Buffers parsed calls and their payload rows, flushes batches to ClickHouse
 * via the HTTP {@code JSONEachRow} insert format. Two tables ({@code calls},
 * {@code payloads}) are populated in lockstep — see
 * {@code clickhouse-init/01-schema.sql} for the DDL.
 *
 * <p>The {@code requests} rollup table is <em>not</em> written by this sink.
 * It is maintained server-side by {@code requests_mv}, a materialized view
 * that reads every insert into {@code calls} and folds it into the rollup
 * via {@code SimpleAggregateFunction} columns. This eliminates the in-memory
 * per-request aggregator that the sink used to hold (which couldn't handle
 * fire-and-forget async work whose late calls arrived after the
 * synchronous root closed — bug B-03).</p>
 *
 * <p>Inserts are best-effort: a failure logs and discards the batch rather
 * than blocking the Kafka consumer or stalling Kafka backlog. Per the
 * "Kafka is a shock absorber, not a parking lot" stance, persistent CH
 * outages are visible operationally and warrant a real fix, not in-process
 * retries that would amplify load.</p>
 */
public class ClickHouseSink implements RecordSink {

    private static final int FLUSH_THRESHOLD = 500;
    private static final long FLUSH_INTERVAL_MS = 1000;
    /** Drop a seenSessions entry whose admission age exceeds this; ReplacingMergeTree dedupes any re-emit. */
    private static final long SESSION_TTL_MS = 60 * 60 * 1000L;
    /** Throttle the eviction sweep — periodicFlush ticks every second. */
    private static final long SESSION_SWEEP_INTERVAL_MS = 5 * 60 * 1000L;
    private static final String EMPTY_HASH = "00000000000000000000000000000000";
    private static final DateTimeFormatter CH_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI insertCallsUri;
    private final URI insertPayloadsUri;
    private final URI insertAgentRunsUri;
    private final URI insertSessionsUri;
    private final HttpClient http;
    private final String basicAuth;
    private final ScheduledExecutorService flusher;

    private final Object lock = new Object();
    private final List<Map<String, Object>> callBuffer = new ArrayList<>();
    private final List<Map<String, Object>> payloadBuffer = new ArrayList<>();
    /**
     * Agent-run rows pending insert, keyed by run id — one row per distinct
     * run between flushes, however many batches it spans. ReplacingMergeTree
     * collapses re-emits across flushes and processor restarts.
     */
    private final Map<UUID, Map<String, Object>> agentRunBuffer = new LinkedHashMap<>();
    private final List<Map<String, Object>> sessionBuffer = new ArrayList<>();

    /**
     * Stateful parser — holds the open-calls map across batches so a request
     * whose MS and ME land in different Kafka polls still pairs correctly.
     * All access is via {@link #accept} which is serialized by {@link #lock}.
     */
    private final RecordParser parser = new RecordParser();

    /**
     * Sessions already announced as a row to ClickHouse, mapped to the
     * processor wall-clock time at which the admission row was buffered.
     * Bound to the processor's lifetime — on restart, sessions are re-emitted;
     * the {@code sessions} table's {@link "ReplacingMergeTree"} engine
     * de-duplicates them on the server. TTL eviction
     * ({@link #SESSION_TTL_MS}) keeps this from growing monotonically over
     * long-lived processor instances; an evicted session that re-appears
     * simply re-emits one row, which RMT collapses.
     */
    private final Map<SessionKey, Long> seenSessions = new HashMap<>();
    private long nextSessionSweepAt;

    public ClickHouseSink(ProcessorConfig config) {
        this.insertCallsUri = buildInsertUri(config, "calls");
        this.insertPayloadsUri = buildInsertUri(config, "payloads");
        this.insertAgentRunsUri = buildInsertUri(config, "agent_runs");
        this.insertSessionsUri = buildInsertUri(config, "sessions");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String userPass = config.getClickhouseUser() + ":" + config.getClickhousePassword();
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));

        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "arachna-trace-ch-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleAtFixedRate(this::periodicFlush,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void accept(List<TraceRecord> records, AgentRun agentRun) {
        boolean thresholdReached;
        synchronized (lock) {
            // The parser is stateful; serialize parsing and row-buffering under
            // the same lock so its open-calls map mutations are well-ordered
            // with respect to the flusher thread.
            List<ParsedCall> calls = parser.parse(records);

            // Authoritative source: transport-layer metadata. Keyed by run id
            // so a run spanning many batches buffers a single row per flush;
            // ReplacingMergeTree collapses re-emits across flushes/restarts.
            UUID runId = agentRun.agentRunId();
            agentRunBuffer.putIfAbsent(runId, agentRunRow(agentRun));

            for (ParsedCall call : calls) {
                addRows(call, runId);
                noteSessionIfNew(call, runId);
                // No per-request aggregation here — the requests_mv on the
                // CH side reads every insert into `calls` and maintains the
                // requests rollup automatically.
            }

            thresholdReached = callBuffer.size() >= FLUSH_THRESHOLD
                    || payloadBuffer.size() >= FLUSH_THRESHOLD;
        }
        // HTTP inserts happen outside the lock — a slow ClickHouse must not
        // stall the Kafka poll loop's next accept() or the parser.
        if (thresholdReached) {
            flush();
        }
    }

    @Override
    public void close() {
        flusher.shutdown();
        try {
            flusher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    private void periodicFlush() {
        synchronized (lock) {
            evictStaleSessions();
        }
        flush();
    }

    void addRows(ParsedCall c, UUID effectiveRunId) {
        Long thisId = c.thisIdRef();
        if (thisId == null && c.thisJson() != null) {
            thisId = extractRootId(c.thisJson());
            payloadBuffer.add(payloadRow(c, effectiveRunId, "TI", c.thisJson()));
        }
        if (c.argsJson() != null) {
            payloadBuffer.add(payloadRow(c, effectiveRunId, "AR", c.argsJson()));
        }
        if (c.argsExitJson() != null) {
            payloadBuffer.add(payloadRow(c, effectiveRunId, "AX", c.argsExitJson()));
        }
        if (c.returnJson() != null) {
            payloadBuffer.add(payloadRow(c, effectiveRunId, "RE", c.returnJson()));
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agent_run_id", uuidToString(effectiveRunId));
        row.put("call_id", uuidToString(c.callId()));
        row.put("parent_call_id", c.parentCallId() != null ? c.parentCallId().toString() : null);
        row.put("session_id", nullToEmpty(c.sessionId()));
        row.put("request_id", c.requestId());
        row.put("thread_name", nullToEmpty(c.threadName()));
        row.put("ts_in", formatTime(c.tsInMillis()));
        row.put("ts_out", formatTime(c.tsOutMillis()));
        row.put("signature", nullToEmpty(c.signature()));
        row.put("caller_line", c.callerLine());
        row.put("return_type", c.returnType() != null ? c.returnType() : "VOID");
        row.put("this_id", thisId);
        row.put("seq", c.seq());
        callBuffer.add(row);
    }

    static Map<String, Object> payloadRow(ParsedCall c, UUID effectiveRunId, String kind, String json) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agent_run_id", uuidToString(effectiveRunId));
        row.put("call_id", uuidToString(c.callId()));
        row.put("session_id", nullToEmpty(c.sessionId()));
        row.put("request_id", c.requestId());
        row.put("ts_in", formatTime(c.tsInMillis()));
        row.put("signature", nullToEmpty(c.signature()));
        row.put("kind", kind);
        row.put("payload_json", json);
        row.put("payload_size", json != null ? json.getBytes(StandardCharsets.UTF_8).length : 0);
        row.put("root_hash", extractRootHash(json));
        ObjectIdCollector.Result envelopes = collectEnvelopes(json);
        row.put("object_ids", envelopes.ids());
        row.put("own_hashes", envelopes.ownHashes());
        row.put("payload_tokens", collectTokens(json));
        row.put("seq", c.seq());
        return row;
    }

    /** ClickHouse UUID columns are non-nullable; the all-zero UUID is the agreed sentinel for null. */
    private static String uuidToString(UUID id) {
        return id != null ? id.toString() : "00000000-0000-0000-0000-000000000000";
    }

    // --- agent_runs / sessions row builders ---

    static Map<String, Object> agentRunRow(AgentRun m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agent_run_id", uuidToString(m.agentRunId()));
        row.put("hostname",       nullToEmpty(m.hostname()));
        row.put("process_pid",    m.processPid());
        row.put("agent_version",  nullToEmpty(m.agentVersion()));
        row.put("code_version",   nullToEmpty(m.codeVersion()));
        row.put("env",            nullToEmpty(m.env()));
        row.put("started_at",     formatTime(m.startedAtMillis()));
        row.put("ended_at",       null);
        row.put("completed_clean", false);
        return row;
    }

    void noteSessionIfNew(ParsedCall c, UUID effectiveRunId) {
        SessionKey key = new SessionKey(effectiveRunId, c.sessionId() != null ? c.sessionId() : "");
        if (seenSessions.putIfAbsent(key, System.currentTimeMillis()) == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("session_id",   key.sessionId());
            row.put("agent_run_id", uuidToString(key.agentRunId()));
            row.put("first_seen",   formatTime(c.tsInMillis()));
            row.put("last_seen",    formatTime(c.tsOutMillis()));
            sessionBuffer.add(row);
        }
        // Updating last_seen on each call would generate one row per call —
        // ReplacingMergeTree-friendly but wasteful. Defer until we have a
        // concrete query that needs accurate last_seen on the live row.
    }

    void evictStaleSessions() {
        long now = System.currentTimeMillis();
        if (now < nextSessionSweepAt) return;
        nextSessionSweepAt = now + SESSION_SWEEP_INTERVAL_MS;
        long cutoff = now - SESSION_TTL_MS;
        seenSessions.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    private record SessionKey(UUID agentRunId, String sessionId) {}

    // --- Test-only accessors (package-private). Mirror the openCallCount()
    //     pattern used by RecordParser: expose state for assertions without
    //     leaking the underlying collections.

    int callBufferSize()      { return callBuffer.size(); }
    int payloadBufferSize()   { return payloadBuffer.size(); }
    int agentRunBufferSize()  { return agentRunBuffer.size(); }
    int sessionBufferSize()   { return sessionBuffer.size(); }
    int seenSessionCount()    { return seenSessions.size(); }

    List<Map<String, Object>> peekCalls()      { return List.copyOf(callBuffer); }
    List<Map<String, Object>> peekPayloads()   { return List.copyOf(payloadBuffer); }
    List<Map<String, Object>> peekSessions()   { return List.copyOf(sessionBuffer); }
    List<Map<String, Object>> peekAgentRuns()  { return List.copyOf(agentRunBuffer.values()); }

    private static String extractRootHash(String hashedJson) {
        try {
            return Hasher.extractRootHashFromHashed(hashedJson);
        } catch (IOException e) {
            return EMPTY_HASH;
        }
    }

    private static Long extractRootId(String hashedJson) {
        Object meta = readRootMeta(hashedJson);
        if (meta instanceof Map<?, ?> m && m.get("id") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Object readRootMeta(String json) {
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return map.get("__meta__");
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    private static ObjectIdCollector.Result collectEnvelopes(String json) {
        try {
            return ObjectIdCollector.collectBoth(json);
        } catch (IOException e) {
            return ObjectIdCollector.Result.empty();
        }
    }

    private static List<String> collectTokens(String json) {
        try {
            return ScalarTokenCollector.collect(json);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String formatTime(long ms) {
        return CH_DATETIME.format(Instant.ofEpochMilli(ms));
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /**
     * Snapshot-and-clear the row buffers under the lock, then POST outside it
     * so ClickHouse latency never blocks {@link #accept}. Inserts are
     * best-effort by design (see class javadoc): rows are dropped on failure,
     * not re-queued.
     */
    void flush() {
        List<Map<String, Object>> runs, sessions, calls, payloads;
        synchronized (lock) {
            runs = List.copyOf(agentRunBuffer.values());
            sessions = List.copyOf(sessionBuffer);
            calls = List.copyOf(callBuffer);
            payloads = List.copyOf(payloadBuffer);
            agentRunBuffer.clear();
            sessionBuffer.clear();
            callBuffer.clear();
            payloadBuffer.clear();
        }
        if (!runs.isEmpty())     postJsonEachRow(insertAgentRunsUri, runs);
        if (!sessions.isEmpty()) postJsonEachRow(insertSessionsUri, sessions);
        if (!calls.isEmpty())    postJsonEachRow(insertCallsUri, calls);
        if (!payloads.isEmpty()) postJsonEachRow(insertPayloadsUri, payloads);
    }

    private void postJsonEachRow(URI uri, List<Map<String, Object>> rows) {
        try {
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> row : rows) {
                body.append(MAPPER.writeValueAsString(row)).append('\n');
            }
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("[ArachnaTrace] CH insert " + uri.getPath()
                        + " failed: HTTP " + resp.statusCode() + " — " + resp.body());
            }
        } catch (IOException e) {
            System.err.println("[ArachnaTrace] CH insert IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[ArachnaTrace] CH insert interrupted");
        }
    }

    private static URI buildInsertUri(ProcessorConfig config, String table) {
        String query = "INSERT INTO " + table + " FORMAT JSONEachRow";
        return URI.create(config.getClickhouseUrl()
                + "/?database=" + config.getClickhouseDatabase()
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }
}
