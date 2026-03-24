# Agent Module

The agent module is the entry point of Deepflow. It is a Java agent that
attaches to a target application via the `-javaagent` JVM flag and instruments
selected classes at load time to capture method calls, arguments, return values,
exceptions, and object identity.

The agent itself produces no output files directly. It encodes captured data
into binary records (see [RECORD-FORMAT.md](../record-format/RECORD-FORMAT.md))
and offers them to an in-memory buffer. A background drainer thread delivers
records to a configured destination (see `serializer` module).

## Startup sequence

```
JVM loads -javaagent
  → DeepFlowAgent.premain(agentArgs, instrumentation)
    1. AgentConfig.getInstance(agentArgs)        Parse config file + CLI args
    2. DeepFlowAdvice.setup(config)              Initialize advice statics
       └→ RecorderManager.create(config)         Create buffer, destination, drainer
          └→ Register JVM shutdown hook           Ensures drain + close on exit
    3. Build ByteBuddy type matchers              From matchers_include / matchers_exclude
    4. Install advice on instrumentation          Intercepts matched methods at class load

  On first instrumented method entry (deferred from startup):
    5. Load SessionIdResolver via SPI             Lazy, uses context classloader
    6. Load JpaProxyResolver via SPI              Lazy, registers with Codec
```

## Key design decisions

### SPI resolvers are loaded lazily

Both SPI resolvers are loaded on the **first instrumented method entry** — not
at agent startup. This is because `premain` runs before the application's
classloaders are initialized. In Spring Boot, the context classloader is not
set up until the application starts, so a `ServiceLoader` call during `premain`
would fail to find resolver implementations on the application classpath.

- **`SessionIdResolver`** — double-checked locking on first `getResolver()`
  call. If `session_resolver` is not configured, a built-in no-op resolver
  (returns `null`) is used without any SPI lookup.
- **`JpaProxyResolver`** — double-checked locking in `initJpaProxyResolver()`,
  called at the top of every `recordEntry`. Once found, the resolver is
  registered with `Codec.setJpaProxyResolver()` so the CBOR encoder can unwrap
  Hibernate proxies during serialization.

### Error isolation

Both `recordEntry` and `recordExit` wrap all work in `try/catch(Throwable)`.
If the agent fails to record a method call (e.g. serialization error), it
prints to `stderr` and continues — the target application is never affected.

### `serialize_values` branching

When `serialize_values=true` (default), the full pipeline runs: CBOR-encode
arguments/return values, wrap in envelopes, write binary record groups
(`METHOD_START` + `THIS_INSTANCE`/`ARGUMENTS` on entry;
`RETURN`/`EXCEPTION` + `METHOD_END` on exit).

When `serialize_values=false`, only structural records are emitted
(`METHOD_START` on entry, `METHOD_END` on exit) — no CBOR serialization
occurs. This mode is for dead code detection where only the call graph matters.

The branching happens in `DeepFlowAdvice.recordEntry`/`recordExit`, which call
different `RecordWriter` methods (`logEntry` vs `logEntrySimple`, `logExit` vs
`logExitSimple`). Everything downstream (buffer, drainer, destination) is
unaware of the distinction.

### Two different "session IDs"

**File-naming session ID** — auto-generated at startup as `yyyyMMdd-HHmmss`.
Used by `FileDestination` to name the output directory
(`SESSION-20260320-213331/`) and thread files. Not configurable.

**Trace-record session ID** — injected into every `METHOD_START` and
`METHOD_END` binary payload via the `SessionIdResolver` SPI. This is the
logical session (HTTP session, request ID, debug run label, etc.). Configured
by `session_resolver` + optionally `session_id`. See
[SESSION-RESOLVER-SPI.md](../../spi/session-resolver-api/SESSION-RESOLVER-SPI.md).

### Config resolution order

Inline key-value pairs from the `-javaagent` argument string (separated by `&`)
are parsed first. If a `config` key is present, the referenced file is loaded.
**Inline values override file values** — this lets you keep a base config file
and tweak individual settings per run:

```bash
java -javaagent:agent.jar=config=deepagent.cfg&destination=file ...
```

### Dependency shading

The agent shares the target application's classloader namespace, so all
third-party dependencies (Jackson, ByteBuddy) are relocated via
`maven-shade-plugin` to `com.github.gabert.deepflow.shaded.*`. Without this,
version conflicts would occur if the target application uses different
versions of the same libraries.

The agent's own modules (`codec`, `record-format`, `serializer`) are bundled
as-is — their packages (`com.github.gabert.deepflow.*`) are unique and don't
conflict.

Signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`) are stripped because
repackaged dependencies carry invalid signatures. JDK 24 multi-release class
files (`META-INF/versions/24/**`) are excluded for compatibility.
