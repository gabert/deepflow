# Serializer Module

The serializer module contains the recording pipeline that transports binary
trace records from the instrumented application threads to an output
destination. It sits between the agent (which produces records) and the outside
world (files, network, etc.).

The module has no knowledge of bytecode instrumentation or the agent itself. It
operates purely on `byte[]` records whose format is defined in the
[record-format](record-format.md) module.

## Data flow

```
Application threads          Background thread           Output
─────────────────          ──────────────────          ─────────
                              RecordDrainer
DeepFlowAdvice               (daemon thread)
  → RecordWriter                   │
    → byte[]                       │
      → buffer.offer()  ──→  buffer.poll()
                               │
                               ▼
                          destination.accept(byte[])
                               │
                               ▼
                          (on JVM shutdown)
                          destination.flush()
                          destination.close()  ──→  .dft files / HTTP / ...
```

## Key design decisions

### Why an unbounded buffer

The buffer (`UnboundedRecordBuffer`, backed by `ConcurrentLinkedQueue`)
never blocks and never drops records. This is the right default for a tracing
agent: dropping records silently would produce incomplete traces that are hard
to diagnose, and blocking the `offer()` call would alter the target
application's timing behavior. Memory grows during bursts and is reclaimed by
GC once the drainer catches up.

### Why the Destination interface receives raw `byte[]`

The `Destination` abstraction operates on raw binary records, not decoded
text. This keeps it at the right level of abstraction:

- `FileDestination` decodes binary to human-readable text (via
  `RecordRenderer`) and writes per-thread `.dft` files.
- A future `HttpDestination` would forward raw binary bytes to a server
  without any decoding overhead.

If the interface operated on decoded text, binary-transport destinations would
pay the decode cost for nothing.

### Shutdown sequence

The `RecorderManager` registers a JVM shutdown hook that calls:
1. `drainer.stop()` — sets the running flag to `false`, joins the thread, then
   drains any remaining records from the buffer and calls `destination.flush()`.
2. `destination.close()` — releases resources (closes file handles, etc.).

The drainer runs as a daemon thread (`setDaemon(true)`), so it doesn't prevent
JVM shutdown on its own — the shutdown hook ensures graceful drain.

### FileDestination: naming vs logical session

`FileDestination` generates a **run timestamp** (`yyyyMMdd-HHmmss`) at
construction time. This is used solely for naming the output directory
(`SESSION-<timestamp>/`) and per-thread files (`<timestamp>-<thread>.dft`).

This is _not_ the logical session ID — the logical session ID comes from the
`SessionIdResolver` SPI and is embedded inside individual binary records.
See [session-resolver-spi.md](session-resolver-spi.md).

## Adding a new destination

1. Create a class implementing `Destination` in the `destination` package.
2. The constructor should accept `Map<String, String>` config and extract
   the parameters it needs (e.g. `url`, `port`).
3. Register the new type in `RecorderManager.createDestination()` in the
   agent module.

The new destination receives raw `byte[]` records — it can forward them
as-is (binary transport) or decode them via `RecordReader` / `RecordRenderer`
(text transport).
