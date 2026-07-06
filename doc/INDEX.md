# Documentation Index

Every documentation file in the repository, grouped by tree, with a
one-line hook. Per-module `README.md` files are omitted; they are
landing pages that point into the trees below.

For the system-wide pipeline diagram see [doc/architecture.md](architecture.md).
For what Arachna Trace is and why, see the [repo root README](../README.md).

---

## Repository root

- [`README.md`](../README.md) — landing page. What Arachna Trace is,
  how it differs from OpenTelemetry / log aggregation, 60-second
  quick start.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — project scope, where
  contribution lands (agent vs. infra), how to get a change merged.

## Solution-level — `doc/`

- [`doc/architecture.md`](architecture.md) — system-wide pipeline
  (agent → collector → Kafka → processor → ClickHouse → UI),
  language-agnostic.

## Wire-format specification — `spec/`

The language-neutral contract any agent or processor must implement.

- [`spec/README.md`](../spec/README.md) — reading order for the
  spec set.
- [`spec/SPEC.md`](../spec/SPEC.md) — top-level wire-format overview.
  Start here.
- [`spec/WIRE-FORMAT.md`](../spec/WIRE-FORMAT.md) — binary frame
  layout, record types, byte order.
- [`spec/CBOR-ENVELOPE.md`](../spec/CBOR-ENVELOPE.md) — identity
  envelope carrying `OBJECT_ID` + `CLASS_NAME` around captured values.
- [`spec/HASHING.md`](../spec/HASHING.md) — Merkle content hashing
  (`hash`, `own_hash`) added by the processor.
- [`spec/TAGS.md`](../spec/TAGS.md) — rendered tag-line text view
  (`MS`, `AR`, `RE`, …) and per-tag semantics.
- [`spec/TRANSPORT.md`](../spec/TRANSPORT.md) — how `AgentRun`
  identity rides on HTTP / Kafka headers (not in-record).
- [`spec/IDENTITY-MODEL.md`](../spec/IDENTITY-MODEL.md) — `object_id`
  contract and cross-language porting trade-offs.
- [`spec/PORTING-GUIDE.md`](../spec/PORTING-GUIDE.md) — checklist
  for writing a Arachna Trace agent in a new language.

## Generic agent docs — `arachna-trace-agents/docs/`

Language-neutral concepts that apply to any agent implementation.

- [`concepts.md`](../arachna-trace-agents/docs/concepts.md) —
  vocabulary (`object_id`, `call_id`, session, request, hash) used
  everywhere else.
- [`reading-a-trace.md`](../arachna-trace-agents/docs/reading-a-trace.md) —
  three worked examples: a calculation bug, a mutation under
- [`ai-code-audit.md`](../arachna-trace-agents/docs/ai-code-audit.md) —
  reviewing AI-generated code by recorded behavior (narrative, behavioral diff, liveness sweep)
  concurrency, a hash drill-down.
- [`serialize-modes.md`](../arachna-trace-agents/docs/serialize-modes.md) —
  `serialize_values=true|false`; full-CBOR vs. call-graph-only.
- [`truncation.md`](../arachna-trace-agents/docs/truncation.md) —
  `max_value_size` cap on individual serialized payloads.
- [`mutation-detection.md`](../arachna-trace-agents/docs/mutation-detection.md) —
  enabling `AX` to capture argument state at both entry and exit.
- [`process/KNOWN_BUGS.md`](../arachna-trace-agents/docs/process/KNOWN_BUGS.md) —
  bug catalog with stable IDs (B/L/D/U/A) and current status.
- [`process/ROADMAP.md`](../arachna-trace-agents/docs/process/ROADMAP.md) —
  open backlog, deferred items, motivation per feature.

## JVM agent docs — `arachna-trace-agents/jvm/docs/`

JVM-specific implementation of the agent contract.

- [`getting-started.md`](../arachna-trace-agents/jvm/docs/getting-started.md) —
  build the JVM agent, attach via `-javaagent`, write a minimal config.
- [`architecture.md`](../arachna-trace-agents/jvm/docs/architecture.md) —
  JVM-internal data flow inside the agent (the only
  language-specific piece of the system).
- [`request-id.md`](../arachna-trace-agents/jvm/docs/request-id.md) —
  how request IDs are assigned per root call and inherited by
  nested calls; JVM `ThreadPoolExecutor` / `ForkJoinPool` propagation.

### Reference

- [`reference/agent-config.md`](../arachna-trace-agents/jvm/docs/reference/agent-config.md) —
  every `arachna-agent.cfg` option.
- [`reference/session-resolver.md`](../arachna-trace-agents/jvm/docs/reference/session-resolver.md) —
  `SessionIdResolver` SPI: inject a logical session ID into every
  record.
- [`reference/jpa-proxy-resolver.md`](../arachna-trace-agents/jvm/docs/reference/jpa-proxy-resolver.md) —
  `JpaProxyResolver` SPI: unwrap Hibernate / JPA proxies during
  serialization.
- [`reference/spi-wiring.md`](../arachna-trace-agents/jvm/docs/reference/spi-wiring.md) —
  end-to-end SPI wiring (interface → impl class →
  `META-INF/services` → classpath → config name).
- [`reference/argument-names.md`](../arachna-trace-agents/jvm/docs/reference/argument-names.md) —
  how the agent resolves parameter names (`MethodParameters` →
  `LocalVariableTable` → positional fallback).

### Internals

- [`internals/agent.md`](../arachna-trace-agents/jvm/docs/internals/agent.md) —
  agent module: ByteBuddy setup, startup sequence, `RecorderManager`.
- [`internals/codec.md`](../arachna-trace-agents/jvm/docs/internals/codec.md) —
  Java CBOR codec: `ObjectIdRegistry`, envelope serializer,
  weak-ref identity.
- [`internals/serializer.md`](../arachna-trace-agents/jvm/docs/internals/serializer.md) —
  recording pipeline (buffer / drainer / destination).
- [`internals/executor-instrumentation.md`](../arachna-trace-agents/jvm/docs/internals/executor-instrumentation.md) —
  `ThreadPoolExecutor` / `ForkJoinPool` patches that propagate
  request ID across async boundaries.

## Infrastructure docs — `arachna-trace-infra/docs/`

Server-side pipeline: collector → Kafka → processor → ClickHouse → query.

### Reference

- [`reference/deployment-modes.md`](../arachna-trace-infra/docs/reference/deployment-modes.md) —
  local / embedded / distributed deployment shapes; size-limit
  alignment contract across the pipeline.
- [`reference/collector-config.md`](../arachna-trace-infra/docs/reference/collector-config.md) —
  `arachna-collector.cfg` options (Netty intake, Kafka producer).
- [`reference/processor-config.md`](../arachna-trace-infra/docs/reference/processor-config.md) —
  `arachna-processor.cfg` options (Kafka consumer, ClickHouse sink).
- [`reference/query-server-config.md`](../arachna-trace-infra/docs/reference/query-server-config.md) —
  `arachna-query.cfg` options (HTTP API, ClickHouse URL).

### Internals

- [`internals/record-format.md`](../arachna-trace-infra/docs/internals/record-format.md) —
  Java binary frame implementation, paired with the collector that
  relays them.
- [`internals/processor.md`](../arachna-trace-infra/docs/internals/processor.md) —
  render → hash → MS/ME pair → ClickHouse insert pipeline.
- [`internals/query-server.md`](../arachna-trace-infra/docs/internals/query-server.md) —
  read-only JSON API, ClickHouse parameter binding, endpoint
  families.

## UI docs — `arachna-trace-ui/docs/`

- [`internals/ui.md`](../arachna-trace-ui/docs/internals/ui.md) —
  Vue / PrimeVue browser: tech stack, narrative model, "surface
  signals, leave judgment to the user" philosophy.
- [`internals/bug-finding.md`](../arachna-trace-ui/docs/internals/bug-finding.md) —
  UI bug-finder design: `hash` vs. `own_hash`, Mutations panel,
  provenance, value search.
