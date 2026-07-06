# Performance — agent overhead on application threads

This page quantifies what attaching the agent costs the traced
application: the added latency per traced method call, measured with and
without the agent on the same workloads. The numbers exist so a team can
decide, from evidence, whether the overhead is acceptable for their
service before attaching the agent.

The agent's architecture keeps everything except capture off the
application thread: records are handed to an in-memory queue and
drained, batched, and shipped by a background thread. What the
application thread pays is capture — building the entry/exit records
and, in full-serialization mode, CBOR-encoding the captured values.
That capture cost is what this page measures.

## Methodology

- Harness: [`benchmarks/`](../../benchmarks/) in this module — a plain
  `main()` loop calling a 3-level service chain, timed with
  `System.nanoTime()` around fixed-size rounds after a JIT warm-up
  phase. Median of 7 rounds is reported. One iteration = 3 traced calls.
- Destination: `http`, posting to a local no-op collector (HTTP 200,
  body discarded), so the numbers reflect the centralised
  (collector → Kafka → ClickHouse) deployment and the drain side does
  not compete for measurement CPU.
- Configuration: defaults (`emit_tags` default set, `parameter_names=true`,
  `expand_this=false`, no session resolver, no truncation).
- Single application thread. Multi-threaded behaviour is discussed below.
- Environment for the published numbers: Intel Core i3-8100 (4 cores,
  3.6 GHz), 48 GB RAM, Windows 11, Temurin OpenJDK 17.0.1, agent built
  from this repository at the commit that added this page.

Two workload shapes:

| Workload | Arguments serialized per call | Approx. envelope nodes | Captured payload (exact, as rendered) |
|---|---|---|---|
| *simple* | small POJO (4 scalar fields, 3-element string list) + primitives | ~6 | [simple-order-ar.json](../../benchmarks/sample-payloads/simple-order-ar.json) (0.4 KB) |
| *business* | invoice aggregate: customer with two addresses, 12 line items with `BigDecimal` prices and attribute maps, metadata map, enum status | ~30 | [business-invoice-ar.json](../../benchmarks/sample-payloads/business-invoice-ar.json) (6.8 KB) |

The *business* shape is the representative case for enterprise data
debugging — a mid-size domain aggregate passed through a service layer.
The linked files are the actual `AR` payloads captured during the
measured runs (JSON rendering of the CBOR on the wire, including the
`__meta__` identity and content-hash envelopes), so the reader can see
exactly what one 23-microsecond capture buys.

## Results

Per traced call (median; iteration time ÷ 3):

| Workload | No agent | Agent, `serialize_values=false` | Agent, full serialization |
|---|---|---|---|
| simple   | 0.009 µs | 3.4 µs | 7.2 µs |
| business | 0.07 µs  | 3.4 µs | 23.4 µs |

Reading the table:

- **Structural mode** (`serialize_values=false`) has a flat cost per
  call (~3–4 µs here) regardless of argument complexity — nothing is
  serialized. This is the mode for call-graph / dead-code work.
- **Full serialization** scales with the size of the data captured:
  ~7 µs for small arguments, ~23 µs for the invoice aggregate. CBOR
  encoding with identity envelopes is the dominant term; record
  assembly, ids, and queue hand-off account for roughly the structural
  baseline.
- The **no-agent** column is the same code with no `-javaagent` flag;
  the methods themselves cost nanoseconds, so the agent columns are,
  in effect, the absolute overhead per traced call.

## Interpreting the ratio correctly

Dividing the columns gives ratios in the hundreds — 23.4 µs against
0.07 µs reads as a 300× slowdown. That ratio is an artifact of the
benchmark's design, not a property the application will experience:

- **The no-agent column is an empty-method floor.** The benchmark
  methods deliberately do almost nothing, so that the measurement
  isolates the agent's cost — which makes overhead 100% of the measured
  time by construction. Methods worth tracing in a real application do
  real work: a JPA query runs 100 µs–10 ms, a downstream HTTP call runs
  milliseconds. The meaningful comparison is the fixed per-call cost
  against what traced methods actually cost:

  | Traced method's own cost | + 23.4 µs capture | Added |
  |---|---|---|
  | database query, ~1 ms | 1.023 ms | +2.3% |
  | service-layer call, ~200 µs | 223 µs | +12% |
  | trivial getter, ~50 ns | — | not instrumented (getters/setters/`toString`/`equals`/`hashCode` and bridge/synthetic methods are excluded) |

- **Traced calls are distributed across the application's flow, never
  concentrated at one point.** A request that touches hundreds of
  traced methods does so across controller, service, and repository
  layers — and often across threads and async hops — over the request's
  whole lifetime. Each capture adds its microseconds where that call
  executes; there is no single place where the totals below land as one
  lump, and captures on concurrent branches overlap rather than add to
  wall-clock time. The per-request sums below are therefore an upper
  bound on the end-to-end latency effect.

## What this means for a real service

Overhead is per *traced* call, so total impact depends on how many
calls a request traces and what they carry:

- A request touching 50 traced methods with small arguments adds
  ~0.4 ms; with business-aggregate arguments throughout, ~1.2 ms.
- A request touching 500 traced methods with business payloads adds
  ~12 ms — noticeable for latency-sensitive endpoints, still usable
  for debugging sessions.

Levers, in order of effect:

1. `matchers_include` / `matchers_exclude` — trace the packages under
   investigation, not the whole application. Overhead is zero for
   uninstrumented classes.
2. `serialize_values=false` — structural tracing at a flat ~3–4 µs per
   call when values aren't needed.
3. `emit_tags` — dropping `AR`/`RE`/`TI` skips their serialization
   individually; dropping `CL` skips the per-entry stack walk.
4. `max_value_size` — caps I/O and storage for oversized values (the
   encode itself still runs; see [Truncation](../../../docs/truncation.md)).
5. `buffer_max_records_per_thread` — bounds agent memory if the drain
   side stalls; drops are counted and reported, never silent.

Memory: per-method metadata is cached once per traced method
(ClassValue-keyed, released on class unload); per-thread state is a few
small objects released with the thread. The record queue is the only
component that grows under load, and it is bounded by the setting above
if required.

Multi-threading: capture is designed to avoid cross-thread contention —
per-thread buffer shards, per-thread caches, `ThreadLocalRandom` call
ids. The single-threaded numbers above are therefore expected to hold
per thread under concurrency; the shared costs that remain (sequence
counter, GC) are not visible at these magnitudes.

## Reproducing

```bash
cd arachna-trace-agents/jvm/benchmarks
bash run.sh            # builds the agent, compiles the harness, runs all scenarios
```

Numbers vary with hardware and JVM; re-measure on hardware
representative of your deployment before drawing conclusions for a
specific service.
