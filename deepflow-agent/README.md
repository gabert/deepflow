# DeepFlow Agent

A Java bytecode instrumentation agent that captures the complete runtime
data flow of your application -- method arguments, return values, exceptions,
object identity, and object mutations -- without any code changes.

## Capabilities

- **Full data capture.** Not just "method X was called" -- you see what it
  was called with, what it returned, and what blew up. Arguments, return
  values, and exceptions are serialized as JSON with type information.

- **Object identity tracking.** Every object instance receives a stable unique
  ID. When the same `Order` passes through `validate`, `calculateTax`, and
  `save`, all three share the same `object_id`. If the contents changed, you
  see which method mutated it.

- **Mutation detection.** Enable AX (arguments at exit) to capture arguments
  both at entry and exit. Compare them to find which methods modify their
  inputs.

- **Request correlation.** Every method call carries a request ID (RI) that
  groups all calls in a single request, including nested calls. Both entry
  and exit records carry the same RI for direct correlation.

- **Value truncation.** Cap serialized payloads via `max_value_size` to
  prevent oversized objects from dominating traces.

- **Per-thread trace files.** Each thread writes to its own `.dft` file
  within a timestamped session directory.

- **Session correlation.** Pluggable session ID resolution via SPI tags
  every trace record with an HTTP session, request ID, or custom key.

- **JPA proxy unwrapping.** Hibernate proxies and collection wrappers are
  resolved to real objects before serialization.

- **Structural-only mode.** `serialize_values=false` records only the call
  graph -- minimal overhead for dead code detection.

- **Cross-thread propagation.** Request IDs propagate across
  `ThreadPoolExecutor` and `ForkJoinPool` submissions so async work shares
  the originating request ID.

- **Zero application dependencies.** Self-contained fat JAR.

## Quick start

### 1. Build

```bash
cd deepflow-agent
mvn clean install
```

Produces `core/agent/target/deepflow-agent.jar`.

### 2. Configure

Create `deepagent.cfg`:

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

### 3. Attach and run

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

Spring Boot with Maven:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

### 4. Read the traces

```bash
ls D:/temp/SESSION-*/
head -30 D:/temp/SESSION-*/main.dft
```

## Trace format

```
VR;1.1
TS;82741936205100
SI;alice-session-01
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
RI;5
CL;42
TI;17
AR;[3]
TE;82741936270000
TN;http-nio-8080-exec-3
RI;5
RT;VALUE
RE;[{"object_id":101,"class":"java.util.ArrayList","value":[...]}]
```

| Tag | Meaning |
|-----|---------|
| `VR` | Format version |
| `TS` | Timestamp at entry (nanoseconds, `System.nanoTime()`) |
| `SI` | Session ID |
| `MS` | Method signature |
| `TN` | Thread name |
| `RI` | Request ID (groups all calls in one request) |
| `CL` | Caller line number |
| `TI` | This instance (object ID or full JSON) |
| `AR` | Arguments as JSON |
| `TE` | Timestamp at exit |
| `RT` | Return type: VOID, VALUE, or EXCEPTION |
| `RE` | Return/exception value as JSON |
| `AX` | Arguments at exit (for mutation detection) |

## Documentation

Full documentation is in [doc/](../doc/):

- [Overview](../doc/overview.md) -- what and why
- [Getting Started](../doc/getting-started.md) -- build, attach, configure
- [Configuration Reference](../doc/configuration.md) -- all options
- [Trace Format](../doc/trace-format.md) -- format specification
- [Architecture](../doc/architecture.md) -- data flow and modules

Features: [Request ID](../doc/features/request-id.md) |
[Truncation](../doc/features/truncation.md) |
[Mutation Detection](../doc/features/mutation-detection.md) |
[Serialize Modes](../doc/features/serialize-modes.md)

## Project structure

```
deepflow-agent/
  deepagent.cfg                     Reference config (all options documented)
  core/
    agent/                          Bytecode instrumentation (entry point)
    codec/                          Object serialization with identity envelopes
    record-format/                  Binary wire format
    serializer/                     Buffer, drainer, file destination
  spi/
    session-resolver-api/           SessionIdResolver SPI interface
    session-resolver-config/        Built-in "config" resolver
    jpa-proxy-resolver-api/         JpaProxyResolver SPI interface
    jpa-proxy-resolver-hibernate/   Hibernate proxy/collection unwrapping
  server/
    record-collector-server/        Netty HTTP server (receives binary records)
  demos/
    demo-spring-boot/               Working Spring Boot example
```
