# Architecture

## Data flow

```
-javaagent flag
  -> DeepFlowAgent.premain()           Load config, set up ByteBuddy
    -> AgentConfig                     Parse deepagent.cfg + CLI args
    -> DeepFlowAdvice (ByteBuddy)      Installed on matched classes/methods
      -> onEnter()                     Capture method args + object IDs
      -> onExit()                      Capture return value, exceptions
        -> Codec.encode()              Serialize to CBOR with identity envelopes
          -> RecordWriter              Produce binary record frames
            -> RecordBuffer            Enqueue for async draining
              -> RecordDrainer         Poll buffer, deliver to destination
                -> FileDestination     Write per-thread .dft files
```

## Module structure

```
deepflow-agent/
  deepagent.cfg                          Reference config

  core/                                  Core modules
    agent/                               Bytecode instrumentation (entry point)
    codec/                               CBOR envelope serialization
    record-format/                       Binary wire format (shared agent <-> server)
    serializer/                          Buffer, drainer, destinations

  spi/                                   Service Provider Interfaces
    session-resolver-api/                SessionIdResolver interface
    session-resolver-config/             Built-in "config" resolver
    jpa-proxy-resolver-api/              JpaProxyResolver interface
    jpa-proxy-resolver-hibernate/        Hibernate proxy/collection unwrapping

  server/                                Server-side components
    record-collector-server/             Netty HTTP server (receives binary records)

  demos/
    demo-spring-boot/                    Spring Boot library app with agent + SPIs
```

## Module boundaries

Each module has a clear responsibility and communicates through defined
interfaces:

**agent** depends on codec, record-format, serializer. Entry point for the
JVM. Instruments classes via ByteBuddy, captures method entry/exit events,
encodes values, writes binary records to a buffer.

**codec** has no internal dependencies. Provides `Codec.encode()` /
`Codec.decode()` for CBOR serialization with identity envelopes. Handles
cycle detection, proxy detection, JPA proxy unwrapping.

**record-format** has no internal dependencies. Defines the binary frame
format (`RecordWriter` / `RecordReader`) shared between agent and server.
This is the wire protocol contract.

**serializer** depends on codec, record-format. Contains the recording
pipeline: `RecordBuffer` (concurrent queue), `RecordDrainer` (background
thread), `Destination` interface, `FileDestination` (per-thread .dft files),
`RecordRenderer` (binary to text conversion).

**SPI modules** define interfaces (`SessionIdResolver`, `JpaProxyResolver`)
with implementations loaded via `ServiceLoader`. They are separate JARs on
the application classpath so they can access framework classes.

## Key design decisions

### Agent's own packages excluded from instrumentation

The agent must never instrument its own classes. `DeepFlowAgent` adds
`com.github.gabert.deepflow` to the exclusion list to prevent infinite
recursion (instrumented method -> recordEntry -> Codec.encode ->
instrumented again -> ...).

### Dependency shading

The agent shares the target application's classloader namespace. All
third-party dependencies (ByteBuddy, Jackson) are relocated via
`maven-shade-plugin` to `com.github.gabert.deepflow.shaded.*` to avoid
version conflicts with the application's own dependencies.

### SPI resolvers loaded lazily

Both SPI resolvers are loaded on the first instrumented method entry, not
at agent startup. `premain` runs before application classloaders are
initialized. In Spring Boot, the context classloader is not ready until the
application starts, so a `ServiceLoader` call during `premain` would fail
to find resolver implementations.

- `SessionIdResolver` -- double-checked locking on first `getResolver()` call.
- `JpaProxyResolver` -- double-checked locking in `initJpaProxyResolver()`.

### Error isolation

Both `recordEntry` and `recordExit` wrap all work in `try/catch(Throwable)`.
If the agent fails (e.g. serialization error), it prints to `stderr` and
continues. The target application is never affected.

### Unbounded buffer

The `RecordBuffer` (backed by `ConcurrentLinkedQueue`) never blocks and never
drops records. Dropping records silently would produce incomplete traces.
Blocking would alter the application's timing behavior. Memory grows during
bursts and is reclaimed by GC once the drainer catches up.

### Destination receives raw bytes

The `Destination` interface operates on raw `byte[]` records, not decoded
text. `FileDestination` decodes to text via `RecordRenderer`. A future
`HttpDestination` forwards raw bytes without decoding overhead.

### Config resolution order

Inline key-value pairs from the `-javaagent` argument string are parsed
first. If a `config` key is present, the referenced file is loaded. Inline
values override file values.

### Shutdown sequence

`RecorderManager` registers a JVM shutdown hook that:
1. `drainer.stop()` -- sets running flag to false, joins the thread, drains
   remaining records, calls `destination.flush()`
2. `destination.close()` -- releases resources

The drainer is a daemon thread, so it doesn't prevent JVM shutdown.
