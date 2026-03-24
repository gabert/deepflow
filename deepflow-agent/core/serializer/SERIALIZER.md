# Serializer Module

The serializer module contains the recording pipeline that transports binary
trace records from the instrumented application threads to an output
destination. It sits between the agent (which produces records) and the outside
world (files, network, etc.).

The module has no knowledge of bytecode instrumentation or the agent itself. It
operates purely on `byte[]` records whose format is defined in the
[record-format](../record-format/RECORD-FORMAT.md) module.

## Package structure

```
recorder/
  buffer/           Thread-safe FIFO queue for binary records
  destination/      Output sinks, drain loop, and text rendering
```

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

## buffer package

The buffer decouples the producer threads (application threads running
instrumented code) from the consumer (drain thread). This is critical because
the producer runs inside the target application's method calls — it must never
block.

### RecordBuffer (interface)

| Method               | Contract                                         |
|----------------------|--------------------------------------------------|
| `offer(byte[])`      | Enqueue a record. Must never block the caller.   |
| `poll() → byte[]`    | Dequeue a record. Returns `null` if empty.       |
| `size() → int`       | Number of records currently buffered.            |
| `isEmpty() → boolean`| True if no records are buffered.                 |

All implementations must be thread-safe for concurrent `offer` from multiple
application threads and `poll` from the single drainer thread.

### UnboundedRecordBuffer

The current implementation, backed by `ConcurrentLinkedQueue<byte[]>`.

- **Never blocks** — `ConcurrentLinkedQueue.add()` is lock-free.
- **Never drops** — the queue is unbounded; memory grows during bursts and
  is reclaimed by GC once the drainer catches up.
- **Ordering** — FIFO. Records are consumed in the order they were offered.

This is the right default for a tracing agent: dropping records silently would
produce incomplete traces that are hard to diagnose, and blocking would alter
the target application's timing behavior.

## destination package

### Destination (interface)

The abstraction for output sinks. Each implementation decides how to process
the raw binary records it receives.

```java
public interface Destination extends Closeable {
    void accept(byte[] record);
    void flush() throws IOException;
}
```

| Method     | Contract                                                    |
|------------|-------------------------------------------------------------|
| `accept()` | Receive a single binary record. Called by the drainer thread.|
| `flush()`  | Flush any buffered data. Called before close.               |
| `close()`  | Write final output and release resources. Inherited from `Closeable`. |

The interface receives **raw binary data**. This is intentional — it keeps the
abstraction at the right level:

- `FileDestination` decodes binary to human-readable text and writes per-thread
  `.dft` files into a session directory.
- A future `HttpDestination` would forward raw binary bytes to a server without
  any decoding.

### RecordDrainer

A daemon thread that polls records from a `RecordBuffer` and delivers them to
a `Destination`.

```
┌──────────────────────────────────────┐
│           RecordDrainer              │
│                                      │
│  while (running) {                   │
│    record = buffer.poll()            │
│    if (record != null)               │
│      destination.accept(record)      │
│    else                              │
│      Thread.onSpinWait()             │
│  }                                   │
└──────────────────────────────────────┘
```

**Lifecycle:**

| Method    | Behavior                                                       |
|-----------|----------------------------------------------------------------|
| `start()` | Sets `running = true`, starts the daemon thread.              |
| `stop()`  | Sets `running = false`, joins the thread, then drains any remaining records from the buffer and calls `destination.flush()`. |

**Error handling:** If `destination.accept()` throws, the drainer logs to
`stderr` and skips that record. The loop continues — a single bad record does
not stop the pipeline.

**Thread model:** The drainer runs as a daemon thread (`setDaemon(true)`), so
it does not prevent JVM shutdown. The `RecorderManager` registers a shutdown
hook that calls `stop()` to ensure remaining records are drained before the
destination is closed.

### RecordRenderer

A stateless utility that converts a batch of binary records into
semicolon-delimited text lines. Used by destinations that produce
human-readable output.

```java
RecordRenderer.Result result = RecordRenderer.render(byte[] data);
// result.threadName()  → "main"
// result.lines()       → ["MS;com.example::Foo.bar()", "TN;main", ...]
```

The renderer decodes CBOR payloads via `Codec.decode()` + `Codec.toReadableJson()`
to produce JSON representations of arguments, return values, and exceptions.

See [RECORD-FORMAT.md — Text rendering](../record-format/RECORD-FORMAT.md#text-rendering)
for the full tag mapping.

### FileDestination

Writes rendered text lines to per-thread `.dft` files inside a session
directory. Each record is flushed immediately, so trace files are readable
while the application is still running.

**Accept phase** (called per record by the drainer):

1. Render binary record to text via `RecordRenderer.render()`.
2. Extract thread name from the render result.
3. Look up (or create) a `BufferedWriter` for that thread.
4. Write text lines and flush.

**Directory and file naming:**

On construction, `FileDestination` generates a **run timestamp**
(`yyyyMMdd-HHmmss`) that is used solely for naming the output directory and
files. This is _not_ the logical session ID — the logical session ID comes
from the `SessionIdResolver` SPI and is embedded inside individual trace
records (see [SESSION-RESOLVER-SPI.md](../../spi/session-resolver-api/SESSION-RESOLVER-SPI.md)).

**Constructor config** (via `Map<String, String>`):

| Key                    | Required | Description                                |
|------------------------|----------|--------------------------------------------|
| `session_dump_location`| Yes      | Parent directory for the session folder     |

Example output for a run with two threads:

```
D:\temp\SESSION-20260320-213331\
  ├── 20260320-213331-main.dft
  └── 20260320-213331-worker-thread.dft
```

## Adding a new destination

To add a new output sink (e.g. HTTP):

1. Create a class implementing `Destination` in the `destination` package.
2. The constructor should accept `Map<String, String>` config and extract
   the parameters it needs (e.g. `url`, `port`).
3. Register the new type in `RecorderManager.createDestination()` in the
   agent module.

The new destination receives raw `byte[]` records — it can forward them
as-is (binary transport) or decode them via `RecordReader` / `RecordRenderer`
(text transport).
