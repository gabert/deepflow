-- DeepFlow ClickHouse schema.
--
-- Two tables, both partitioned by day, TTL 30 days:
--
--   calls    : one row per method invocation. Light. For session/request/time-slice queries.
--   payloads : one row per (call, kind in {TI, AR, AX, RE}). Heavy JSON + extracted hash and
--              object-id index. For object-identity search and mutation detection.
--
-- Both insert paths are populated by RecordProcessorServer.

CREATE TABLE IF NOT EXISTS deepflow.calls
(
    session_id   String,
    request_id   UInt64,
    thread_name  LowCardinality(String),
    ts_in        DateTime64(3),
    ts_out       DateTime64(3),
    duration_ms  Int64                     MATERIALIZED dateDiff('millisecond', ts_in, ts_out),
    signature    LowCardinality(String),
    caller_line  Int32,
    return_type  Enum8('VOID' = 0, 'VALUE' = 1, 'EXCEPTION' = 2),
    this_id      Nullable(Int64),
    inserted_at  DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts_in)
ORDER BY (session_id, request_id, ts_in)
TTL toDateTime(ts_in) + INTERVAL 30 DAY;

CREATE TABLE IF NOT EXISTS deepflow.payloads
(
    session_id    String,
    request_id    UInt64,
    ts_in         DateTime64(3),
    signature     LowCardinality(String),
    kind          Enum8('TI' = 0, 'AR' = 1, 'AX' = 2, 'RE' = 3),
    payload_json  String,
    root_hash     FixedString(32),
    object_ids    Array(Int64),
    inserted_at   DateTime DEFAULT now(),

    INDEX idx_object_ids object_ids TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts_in)
ORDER BY (session_id, request_id, kind, ts_in)
TTL toDateTime(ts_in) + INTERVAL 30 DAY;
