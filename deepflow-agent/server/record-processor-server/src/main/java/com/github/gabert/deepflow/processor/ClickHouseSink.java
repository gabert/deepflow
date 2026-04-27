package com.github.gabert.deepflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.deepflow.codec.Hasher;
import com.github.gabert.deepflow.codec.ObjectIdCollector;
import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Buffers parsed calls and their payload rows, flushes batches to ClickHouse
 * via the HTTP {@code JSONEachRow} insert format. Two tables ({@code calls},
 * {@code payloads}) are populated in lockstep — see
 * {@code clickhouse-init/01-schema.sql} for the DDL.
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
    private static final String EMPTY_HASH = "00000000000000000000000000000000";
    private static final DateTimeFormatter CH_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI insertCallsUri;
    private final URI insertPayloadsUri;
    private final HttpClient http;
    private final String basicAuth;
    private final ScheduledExecutorService flusher;

    private final Object lock = new Object();
    private final List<Map<String, Object>> callBuffer = new ArrayList<>();
    private final List<Map<String, Object>> payloadBuffer = new ArrayList<>();

    public ClickHouseSink(ProcessorConfig config) {
        this.insertCallsUri = buildInsertUri(config, "calls");
        this.insertPayloadsUri = buildInsertUri(config, "payloads");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String userPass = config.getClickhouseUser() + ":" + config.getClickhousePassword();
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));

        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deepflow-ch-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleAtFixedRate(this::periodicFlush,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void accept(Result result) {
        List<ParsedCall> calls = RecordParser.parse(result);
        synchronized (lock) {
            for (ParsedCall call : calls) {
                addRows(call);
            }
            if (callBuffer.size() >= FLUSH_THRESHOLD || payloadBuffer.size() >= FLUSH_THRESHOLD) {
                flushLocked();
            }
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
        synchronized (lock) {
            flushLocked();
        }
    }

    private void periodicFlush() {
        synchronized (lock) {
            if (!callBuffer.isEmpty() || !payloadBuffer.isEmpty()) {
                flushLocked();
            }
        }
    }

    private void addRows(ParsedCall c) {
        Long thisId = c.thisIdRef();
        if (thisId == null && c.thisJson() != null) {
            thisId = extractRootId(c.thisJson());
            payloadBuffer.add(payloadRow(c, "TI", c.thisJson()));
        }
        if (c.argsJson() != null) {
            payloadBuffer.add(payloadRow(c, "AR", c.argsJson()));
        }
        if (c.argsExitJson() != null) {
            payloadBuffer.add(payloadRow(c, "AX", c.argsExitJson()));
        }
        if (c.returnJson() != null) {
            payloadBuffer.add(payloadRow(c, "RE", c.returnJson()));
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", nullToEmpty(c.sessionId()));
        row.put("request_id", c.requestId());
        row.put("thread_name", nullToEmpty(c.threadName()));
        row.put("ts_in", formatTime(c.tsInMillis()));
        row.put("ts_out", formatTime(c.tsOutMillis()));
        row.put("signature", nullToEmpty(c.signature()));
        row.put("caller_line", c.callerLine());
        row.put("return_type", c.returnType() != null ? c.returnType() : "VOID");
        row.put("this_id", thisId);
        callBuffer.add(row);
    }

    private static Map<String, Object> payloadRow(ParsedCall c, String kind, String json) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", nullToEmpty(c.sessionId()));
        row.put("request_id", c.requestId());
        row.put("ts_in", formatTime(c.tsInMillis()));
        row.put("signature", nullToEmpty(c.signature()));
        row.put("kind", kind);
        row.put("payload_json", json);
        row.put("root_hash", extractRootHash(json));
        row.put("object_ids", collectIds(json));
        return row;
    }

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

    private static List<Long> collectIds(String json) {
        try {
            Set<Long> ids = ObjectIdCollector.collect(json);
            return new ArrayList<>(ids);
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

    private void flushLocked() {
        if (!callBuffer.isEmpty()) {
            postJsonEachRow(insertCallsUri, callBuffer);
            callBuffer.clear();
        }
        if (!payloadBuffer.isEmpty()) {
            postJsonEachRow(insertPayloadsUri, payloadBuffer);
            payloadBuffer.clear();
        }
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
                System.err.println("[DeepFlow] CH insert " + uri.getPath()
                        + " failed: HTTP " + resp.statusCode() + " — " + resp.body());
            }
        } catch (IOException e) {
            System.err.println("[DeepFlow] CH insert IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[DeepFlow] CH insert interrupted");
        }
    }

    private static URI buildInsertUri(ProcessorConfig config, String table) {
        String query = "INSERT INTO " + table + " FORMAT JSONEachRow";
        return URI.create(config.getClickhouseUrl()
                + "/?database=" + config.getClickhouseDatabase()
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }
}
