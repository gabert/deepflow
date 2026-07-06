# Agent overhead benchmarks

Measures the per-call cost the agent adds to application threads. The
results and their interpretation live in
[docs/reference/performance.md](../docs/reference/performance.md).

## Layout

| Path | Purpose |
|---|---|
| `src/bench/` | Instrumented workload classes (compiled with `-g`): simple POJO scenario (`BenchTarget`/`Order`) and enterprise-shaped scenario (`InvoiceService`/`Invoice`) |
| `src-stripped/bench/` | Same shape compiled with `-g:none` — exercises the no-debug-info parameter-name path |
| `src-run/benchrun/` | The un-instrumented runner (`matchers_include=bench\..*` keeps it out of the trace); also starts a local no-op collector on port 18099 for `destination=http` runs |
| `sample-payloads/` | The exact `AR` payloads captured during the published runs (JSON rendering of the on-wire CBOR, `__meta__` envelopes included) — what one capture actually serializes |
| `bench.cfg`, `bench-http.cfg` | Agent configs for file / http destinations |
| `run.sh` | Builds, compiles, runs every scenario with and without the agent |

## Running

```bash
bash run.sh
```

One iteration = 3 traced calls (`process -> validate -> settle`).
Reported medians are ns per iteration; divide by 3 for per-call cost.
Rounds are timed with `System.nanoTime()` after a warm-up phase; the
drain side runs on its own thread and a no-op collector, so the numbers
isolate what the *application thread* pays.
