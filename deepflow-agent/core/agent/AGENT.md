# Agent Module

The agent module is the entry point of Deepflow. It is a Java agent that
attaches to a target application via the `-javaagent` JVM flag and instruments
selected classes at load time to capture method calls, arguments, return values,
exceptions, and object identity.

The agent itself produces no output files directly. It encodes captured data
into binary records (see [RECORD-FORMAT.md](../record-format/RECORD-FORMAT.md))
and offers them to an in-memory buffer. A background drainer thread delivers
records to a configured destination (see `serializer` module).

## Source files

| Class              | Responsibility                                          |
|--------------------|---------------------------------------------------------|
| `DeepFlowAgent`    | `premain` entry point — loads config, builds ByteBuddy matchers, installs advice |
| `AgentConfig`      | Parses `deepagent.cfg` + inline CLI overrides           |
| `DeepFlowAdvice`   | ByteBuddy `@Advice` — intercepts method enter and exit  |
| `RecorderManager`  | Wires buffer, drainer, and destination; owns lifecycle   |

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
```

## Bytecode instrumentation

The agent uses [ByteBuddy](https://bytebuddy.net/) `Advice` to inject code at
method entry and exit of matched classes. No bytecode is modified on disk — the
transformation happens in memory as classes are loaded.

### Class matching

Classes to instrument are selected by regex patterns from the config:

- **`matchers_include`** — comma-separated list of regexes. A class is
  instrumented if its fully-qualified name matches any regex.
- **`matchers_exclude`** — comma-separated list of regexes. Matching classes
  are excluded even if they match an include pattern.

### Method filtering

Within matched classes, all methods are instrumented **except**:

| Method        | Reason for exclusion                                   |
|---------------|--------------------------------------------------------|
| `toString()`  | Called during serialization — would cause infinite recursion |
| `equals()`    | Called by collections during serialization              |
| `hashCode()`  | Called by collections during serialization              |

### Self-exclusion

The agent excludes its own packages from instrumentation to prevent infinite
recursion (intercepting a method would call the codec, which would trigger
another interception):

| Excluded package prefix                      | Contains                   |
|----------------------------------------------|----------------------------|
| `com.github.gabert.deepflow.recorder`        | Buffer, drainer, destinations |
| `com.github.gabert.deepflow.codec`           | CBOR encoding/decoding     |
| `com.github.gabert.deepflow.shaded`          | Relocated third-party libs |

Classes containing `$$` in their name (ByteBuddy-generated proxies, lambdas)
are also excluded.

## Method interception

### On method enter (`DeepFlowAdvice.onEnter`)

Captures:

| Data               | Source                                         |
|--------------------|------------------------------------------------|
| Method signature   | `java.lang.reflect.Method` — formatted as `package::Class.method(params) -> return [modifiers]` |
| Thread name        | `Thread.currentThread().getName()`             |
| Timestamp          | `System.currentTimeMillis()`                   |
| Call depth          | Per-thread `ThreadLocal<Integer>` counter      |
| Caller line number | `StackWalker` — source line of the call site   |
| `this` instance    | Full CBOR encoding (if `expand_this=true`) or object ID reference (default) |
| Arguments          | `Codec.encode(Object[])` — CBOR with envelope  |

The captured data is written as a binary record group (`METHOD_START` +
optional `THIS_INSTANCE`/`THIS_INSTANCE_REF` + `ARGUMENTS`) via `RecordWriter`
and offered to the shared `RecordBuffer`.

### On method exit (`DeepFlowAdvice.onExit`)

Captures:

| Data               | Source                                         |
|--------------------|------------------------------------------------|
| Thread name        | `Thread.currentThread().getName()`             |
| Timestamp          | `System.currentTimeMillis()`                   |
| Return value       | `Codec.encode(returned)` — or empty for void   |
| Exception (if any) | `Map{"message": ..., "stacktrace": [...]}` encoded as CBOR |

Written as a binary record group (`RETURN`/`EXCEPTION` + `METHOD_END`).

### Error isolation

Both `onEnter` and `onExit` wrap all work in `try/catch(Throwable)`. If the
agent fails to record a method call (e.g. serialization error), it prints to
`stderr` and continues — the target application is never affected.

## RecorderManager lifecycle

`RecorderManager.create(config)` wires the recording pipeline:

```
RecordBuffer (UnboundedRecordBuffer)
  → RecordDrainer (daemon thread, polls buffer)
    → Destination (created by factory from config)
```

The destination is selected by the `destination` config property:

| Value  | Destination class  | Behavior                                |
|--------|--------------------|-----------------------------------------|
| `file` | `FileDestination`  | Writes per-thread `.dft` files, flushes each record |

The destination constructor receives a `Map<String, String>` config with
`session_dump_location`. Future destinations (e.g. HTTP) would receive their
own parameters through the same map.

A **JVM shutdown hook** calls `drainer.stop()` (which drains remaining records
and flushes) followed by `destination.close()` (which writes output).

## Configuration

Config is loaded from a properties file (`deepagent.cfg`) with optional inline
overrides via the agent argument string.

### Config file format

```properties
# Lines starting with # are comments
key=value
```

### Supported properties

| Property               | Default | Description                                   |
|------------------------|---------|-----------------------------------------------|
| `session_dump_location`| (required) | Directory where output files are written   |
| `matchers_include`     | (empty) | Comma-separated regexes of classes to instrument |
| `matchers_exclude`     | (empty) | Comma-separated regexes of classes to exclude |
| `destination`          | `file`  | Output destination type                       |
| `expand_this`          | `false` | If `true`, serialize full `this` object; if `false`, record only the object ID |
| `serialize_values`     | `true`  | If `false`, skip CBOR serialization entirely — record only method signatures, call depth, and timestamps (dead code detection mode) |
| `session_resolver`     | (none)  | Name of the `SessionIdResolver` SPI to activate (see [SESSION-RESOLVER-SPI.md](../../spi/session-resolver-api/SESSION-RESOLVER-SPI.md)) |
| `session_id`           | (none)  | Custom session ID — published as system property `deepflow.session_id` for the `config` resolver |
| `jpa_proxy_resolver`   | (none)  | Name of the `JpaProxyResolver` SPI to activate (see [JPA-PROXY-RESOLVER-SPI.md](../../spi/jpa-proxy-resolver-api/JPA-PROXY-RESOLVER-SPI.md)) |

### Config resolution order

1. Parse inline `key=value` pairs from the `-javaagent` argument string
   (separated by `&`)
2. If a `config` key is present, load the referenced file
3. Inline values override file values

Example:

```bash
# File values from deepagent.cfg, but destination overridden to file
java -javaagent:agent.jar=config=deepagent.cfg&destination=file ...
```

### Session ID

There are two distinct session ID concepts:

**File-naming session ID** — auto-generated at startup in the format
`yyyyMMdd-HHmmss` (e.g. `20260320-213331`). Used by destinations to name
output files (e.g. `SESSION-20260320-213331/`). Not configurable.

**Trace-record session ID** — injected into method entry records via the
`SessionIdResolver` SPI. Configured by `session_resolver` (selects the
resolver by name) and optionally `session_id` (value for the `config`
resolver). See [SESSION-RESOLVER-SPI.md](../../spi/session-resolver-api/SESSION-RESOLVER-SPI.md) for
the full guide.

## Dependency shading

The agent is packaged as a single fat JAR (`deepflow-agent.jar`) via
`maven-shade-plugin`. Because the agent shares the target application's
classloader namespace, all third-party dependencies are relocated to prevent
version conflicts.

### Relocation table

| Original package         | Relocated to                                               |
|--------------------------|------------------------------------------------------------|
| `com.fasterxml.jackson`  | `com.github.gabert.deepflow.shaded.com.fasterxml.jackson`  |
| `net.bytebuddy`          | `com.github.gabert.deepflow.shaded.net.bytebuddy`          |

The agent's own modules (`codec`, `record-format`, `serializer`) are bundled
as-is — their packages (`com.github.gabert.deepflow.*`) are unique.

### Bundled dependencies

| Module / Dependency          | Purpose                              |
|------------------------------|--------------------------------------|
| `DeepFlowCodec`              | CBOR serialization of captured data  |
| `DeepFlowRecordFormat`       | Binary wire format for trace records |
| `DeepFlowSerializer`         | Buffer, drainer, and destinations    |
| `SessionResolverApi`         | `SessionIdResolver` SPI interface    |
| `JpaProxyResolverApi`        | `JpaProxyResolver` SPI interface     |
| `jackson-databind`           | JSON/CBOR object mapping (shaded)    |
| `jackson-dataformat-cbor`    | CBOR binary format support (shaded)  |
| `byte-buddy`                 | Bytecode instrumentation (shaded)    |

### Excluded resources

Signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`) are stripped — repackaged
dependencies carry invalid signatures. JDK 24 multi-release class files
(`META-INF/versions/24/**`) are excluded for compatibility.

## Build

```bash
cd deepflow-agent/core/agent
mvn clean install
# Output: target/deepflow-agent.jar
```

## Usage

```bash
java -javaagent:deepflow-agent.jar=config=deepagent.cfg \
     -cp <your-app.jar> com.example.MainClass
```
