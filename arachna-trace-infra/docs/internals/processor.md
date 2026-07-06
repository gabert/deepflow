# Processor Server

`server/record-processor-server` is the workshop: it pulls binary
frames out of Kafka, decodes them to JSON, walks the JSON to
inject content hashes, pairs method-start frames with their
matching method-end frames, and inserts the result into
ClickHouse.

For the agent's wire frames that arrive here, see
[record-format.md](record-format.md). For the high-level
pipeline shape (agent → CH), see
[../../../doc/architecture.md](../../../doc/architecture.md).

## Pipeline shape

For each Kafka record:

```
KafkaRecordConsumer.pollLoop()
  └─ processRecord(record)
       ├─ extractAgentRun(record.headers())  # X-Arachna-Trace-* → AgentRun; null → drop batch
       ├─ RecordReader.readAll(record.value())      # bytes → typed TraceRecords
       └─ sink.accept(records, agentRun)
            └─ ClickHouseSink                       # buffered INSERT JSONEachRow
                 ├─ RecordParser.parse(records)     # pair MS↔ME by call_id → ParsedCall;
                 │                                  # per TI/AR/AX/RE payload: PayloadDecoder
                 │                                  # (CBOR → JSON) + RecordHashEnricher (__meta__)
                 ├─ ObjectIdCollector.collect(...)  # walk hashed JSON → object_ids[]
                 ├─ ScalarTokenCollector.collect()  # walk hashed JSON → payload_tokens[]
                 └─ flush() every 1 s or 500 rows   # HTTP POST happens outside the buffer lock
```

The payload decoding (`PayloadDecoder`) and hash enrichment
(`RecordHashEnricher`) live in `arachna-trace-shared/renderer/`, not
the processor module — the file destination uses the same code to
produce `.dft` files. One implementation, two deployment paths.

## Entry point

```
RecordProcessorServer.main()
  ├─ ProcessorConfig.load(args)
  ├─ createSink(config)
  │    └─ "clickhouse" → ClickHouseSink
  │       "logging"    → LoggingSink            # opt-in, stdout for debugging
  ├─ KafkaRecordConsumer(config, sink)
  └─ consumer.pollLoop()                        # blocks until shutdown
```

Shutdown hook calls `consumer.shutdown()` (wakeup the poller),
then `consumer.close()` (closes the consumer and the sink).

## Kafka consumer

`KafkaRecordConsumer`:

- One consumer subscribed to `arachna-trace-records` (or whatever
  `kafka_topic` is set to).
- `auto.offset.reset=earliest`, `enable.auto.commit=true`.
- 500 ms poll timeout.
- Single-threaded poll loop — no per-thread fanout. The work
  itself (render + hash + parse + insert) is fast enough at
  expected load that one consumer keeps up. If it doesn't, scale
  horizontally by running more processor instances with the same
  consumer group.

`extractAgentRun(record.headers())` lifts the seven
`X-Arachna-Trace-*` Kafka headers (set by the collector) into an
`AgentRun` record. A batch with a missing or unparseable
`agent_run_id` header returns null `AgentRun`, and the consumer
drops it with an error log before any sink runs — see
[../spec/TRANSPORT.md](../../../spec/TRANSPORT.md) for the rationale.

## Why stateful UUID-keyed pairing

`RecordParser` pairs each `TS` (entry) with its matching `TE`
(exit) **by `call_id` UUID**, not by stack ordering.

The previous implementation used a method-local
`ArrayDeque`-as-stack discarded when `parse()` returned. That had
a latent bug: a request whose `TS` arrived in Kafka batch N and
matching `TE` arrived in batch N+1 was silently dropped — the
in-flight builder sat in the local stack and was thrown away.
Multi-thread interleaving in one batch also pretended to share
one stack, mispairing across threads.

The current implementation keeps a `Map<UUID, Builder> openCalls`
that **persists across `parse()` invocations**, so a late `TE` can
find its `TS` no matter which batch each lived in. Multi-thread
interleaving works because every call is uniquely addressable by
id.

### TTL eviction prevents leaks

A `TS` whose `TE` never arrives (agent crashed mid-call, network
drop) would otherwise leak forever. At the end of every `parse()`
call, entries older than `OPEN_CALL_TTL_MS` (10 minutes) are
swept. The sweep itself is throttled to `SWEEP_INTERVAL_MS` (60 s)
so the O(n) cost is amortised across many batches. A late `TE`
for an evicted call is treated as an orphan and dropped silently.

The clock is injectable for tests — see
`RecordParserTest.staleOpenCallIsEvictedAfterTtl` which drives a
fake clock past TTL and asserts `openCallCount()` drains.

### Exit-order quirk

The agent emits exit records in this wire order: `METHOD_END`
(carries the exit timestamp and `call_id`), then `RETURN` /
`EXCEPTION`, then `ARGUMENTS_EXIT`. So on the wire, the method-end
frame comes **before** the call's own return/exception and exit-args
frames.

The parser tracks "exit context" after a `METHOD_END`: subsequent
`RETURN` / `EXCEPTION` / `ARGUMENTS_EXIT` records attach to the
just-closed call's builder until the next `METHOD_START` /
`METHOD_END` resets context.

## ClickHouseSink

Inserts batched rows into `arachna_trace.calls`, `arachna_trace.payloads`,
and `arachna_trace.agent_runs` via the ClickHouse HTTP `INSERT
JSONEachRow` format.

- **Flush triggers**: every 1 s on a scheduled tick, or when the
  in-memory buffer reaches 500 rows.
- **Best-effort inserts**: a failure logs to stderr and discards
  the batch. The `requests` rollup table is *not* written by the
  sink — it's an `AggregatingMergeTree` maintained server-side by
  the `requests_mv` materialized view, which folds every
  `calls`-insert into the rollup. This eliminated the in-memory
  per-request aggregator (and the async-after-root undercount it
  caused — see bug B-03 in [../process/KNOWN_BUGS.md](../../../arachna-trace-agents/docs/process/KNOWN_BUGS.md)).
- **Session deduplication**: `seenSessions` is a
  `Map<SessionKey, admittedAtMs>` with TTL eviction. A re-emit of
  an evicted session produces one duplicate `sessions` row, which
  the `ReplacingMergeTree` engine collapses server-side. Sweep
  cadence: every 5 minutes, drop entries older than 1 hour.
- **Agent-run rows deduped by run id**: between flushes the sink
  buffers at most one `agent_runs` row per distinct run id;
  re-emits across flushes and restarts collapse server-side via
  `ReplacingMergeTree`. A single CH-insert failure isn't fatal
  because the next flush window re-issues it.

The two columns derived at insert time:

- `payloads.object_ids: Array(Int64)` — collected by
  `ObjectIdCollector.collect()` walking the hashed JSON for every
  `__meta__.id`. Bloom-filter indexed for "find every call that
  touched instance N".
- `payloads.payload_tokens: Array(String)` — collected by
  `ScalarTokenCollector.collect()`, every distinct canonicalized
  scalar in the payload tree (strings, numbers, booleans).
  Bloom-filter indexed for value-search and provenance lookups.

The schema DDL is in `server/clickhouse-init/01-schema.sql`.

## Alternative sink: LoggingSink

Opt-in via `sink_type=logging` in `ProcessorConfig`. Renders every
batch to tag lines and prints them to stdout. Used for end-to-end debugging when
ClickHouse isn't part of the loop — useful for testing the agent
→ collector → Kafka segment in isolation.

## Source files

`server/record-processor-server/src/main/java/com/github/gabert/arachna/trace/processor/`

- `RecordProcessorServer.java` — entry point, wires the sink
- `ProcessorConfig.java` — Kafka config + sink_type
- `KafkaRecordConsumer.java` — poll loop, header extraction
- `RecordSink.java` — `accept(List<TraceRecord>, AgentRun)` interface
- `ClickHouseSink.java` — buffered HTTP inserts, periodic flush
- `LoggingSink.java` — stdout sink, opt-in
- `RecordParser.java` — UUID-keyed `MS`↔`ME` pairer with TTL
- `ParsedCall.java` — value class output by the parser
- `ObjectIdCollector.java` — walks hashed JSON for `object_ids[]`
- `ScalarTokenCollector.java` — walks hashed JSON for
  `payload_tokens[]`
