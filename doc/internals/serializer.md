# Serializer Module

The serializer module is the recording pipeline that transports binary trace
records from the instrumented application threads to an output destination.
It sits between the agent (which produces records) and the outside world
(files, network, etc.).

The module operates purely on `byte[]` records whose format is defined in
[record-format.md](record-format.md). It has no knowledge of bytecode
instrumentation.

## Data flow

```
Application threads          Background thread           Output
-----------------          ------------------          ------
                              RecordDrainer
DeepFlowAdvice               (daemon thread)
  -> RecordWriter                   |
    -> byte[]                       |
      -> buffer.offer()  -->  buffer.poll()
                               |
                               v
                          destination.accept(byte[])
                               |
                               v
                          (on JVM shutdown)
                          destination.flush()
                          destination.close()  -->  .dft files / HTTP / ...
```

## Components

### RecordBuffer

`UnboundedRecordBuffer` backed by `ConcurrentLinkedQueue`. Never blocks,
never drops. Memory grows during bursts and is reclaimed by GC once the
drainer catches up.

Why unbounded: dropping records silently produces incomplete traces that are
hard to diagnose. Blocking would alter the target application's timing.

### RecordDrainer

Background daemon thread that polls the buffer and delivers records to the
destination. On shutdown (via JVM hook): stops, joins, drains remaining
records, flushes.

### Destination interface

Receives raw `byte[]` records. Implementations decide how to handle them:

- `FileDestination` -- decodes binary to text via `RecordRenderer`, writes
  per-thread `.dft` files
- `HttpDestination` -- forwards raw binary bytes to a collector server

### FileDestination

Generates a run timestamp (`yyyyMMdd-HHmmss`) at construction. Creates
output directory `SESSION-<timestamp>/` under `session_dump_location`. Each
thread gets its own file: `<timestamp>-<threadname>.dft`.

The first record written to each file is a `VERSION` record (rendered as
`VR;1.1`).

Files are flushed after each record -- traces are readable while the
application is still running.

Note: the file-naming timestamp is not the logical session ID. The logical
session ID comes from `SessionIdResolver` SPI and is embedded inside binary
records.

### RecordRenderer

Converts binary record frames to `TAG;value` text lines. Stateless -- each
`render(byte[])` call processes one complete record group and returns the
lines plus the thread name.

CBOR payloads are decoded via `Codec.decode()` + `Codec.toReadableJson()`.

## Adding a new destination

1. Create a class implementing `Destination` in the `destination` package.
2. Constructor accepts `Map<String, String>` config.
3. Register in `RecorderManager.createDestination()` in the agent module.

The destination receives raw `byte[]` records -- forward as-is for binary
transport, or decode via `RecordReader` / `RecordRenderer` for text.

## Shutdown sequence

`RecorderManager` registers a JVM shutdown hook:

1. `drainer.stop()` -- sets running flag to false, joins thread, drains
   remaining records, calls `destination.flush()`
2. `destination.close()` -- releases resources (file handles, etc.)
