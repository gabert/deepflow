# Deepflow Agent

A Java bytecode instrumentation agent that captures the complete runtime
data flow of your application — method arguments, return values, exceptions,
object identity, and object mutations — without any code changes.

Most debugging starts with "what value did this method actually get?" and
ends with twenty `System.out.println` calls and a redeploy. Deepflow skips
that. Attach it via `-javaagent`, point it at your packages, reproduce the
problem, and read the trace.

## Capabilities

- **Full data capture.** Not just "method X was called" — you see *what* it
  was called with, what it returned, and what blew up. Arguments, return
  values, and exceptions are serialized as JSON with type information.

- **Object identity tracking.** Every object instance receives a stable unique
  ID that persists for the object's lifetime. When the same `Order` object
  passes through `validate`, `calculateTax`, and `save`, all three trace
  entries share the same `object_id`. If the object's contents changed between
  calls, you see exactly which method mutated it.

- **Per-thread trace files.** Each thread writes to its own `.dft` file
  within a timestamped session directory. No interleaved mess — just a
  clean, linear story per thread.

- **Session correlation.** Pluggable session ID resolution tags every trace
  record with an HTTP session, request ID, or any custom correlation key.
  Built-in resolvers for static config values and Spring HTTP sessions;
  custom resolvers via `SessionIdResolver` SPI.

- **JPA proxy unwrapping.** Hibernate lazy-loading proxies and collection
  wrappers (`PersistentBag`, `PersistentSet`) are resolved to their real
  objects before serialization. Without this, proxied entities appear as
  `<proxy>` in traces. Enabled via `JpaProxyResolver` SPI.

- **Structural-only mode.** Setting `serialize_values=false` disables all
  data serialization and records only the call graph — method signatures,
  call depth, and timestamps. Minimal overhead, useful for dead code
  detection.

- **Zero application dependencies.** The agent JAR is a self-contained fat
  JAR. No annotations to sprinkle, no SDK to integrate, nothing to add to
  your `pom.xml`.

## Use cases

### Debugging data errors

Attach the agent, reproduce the failing scenario, read the trace. You see
the exact values flowing through every method — find where the correct value
goes in and the wrong one comes out. No log statements, no redeployment.

### Understanding unfamiliar code

New to the codebase? Instrument the relevant packages, trigger a user flow,
and read the trace. You get the real execution path with real data — better
than any architecture diagram.

### Debugging concurrent and multi-user issues

Enable session tracking and run a multi-user scenario. Each user's requests
are tagged with their session ID, each thread has its own trace file. When
two threads touch the same object (same `object_id`), you see who modified
what and when.

### Finding dead code

Set `serialize_values=false` and run your test suite or just click through
the app. Any method that doesn't show up in the traces was never called.
More precise than static analysis — catches reflection, dynamic dispatch,
and all the framework magic that static tools miss.

### Investigating Hibernate and JPA proxy issues

Enable `jpa_proxy_resolver=hibernate` and the agent unwraps lazy-loading
proxies before serialization. See actual entity state instead of `<proxy>`
markers. Identify whether the issue is in your query, transaction boundary,
or DTO mapping.

### Comparing working vs broken behavior

Run the same scenario before and after a code change. Diff the two trace
files. You'll see exactly which method now returns different data or takes
a different path — no guesswork.

## Quick start

### 1. Build

```bash
cd deepflow-agent
mvn clean install
```

This produces `core/agent/target/deepflow-agent.jar`.

### 2. Configure

Create `deepagent.cfg` (or copy the [reference config](deepagent.cfg) which
documents all options):

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

`matchers_include` is a comma-separated list of regexes matched against
fully-qualified class names. Only matched classes are instrumented.

### 3. Attach and run

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

For Spring Boot with Maven:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

### 4. Read the traces

Output is written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/` with
one `.dft` file per thread. Files are flushed after each record — readable while
the app is still running.

```bash
ls D:/temp/SESSION-*/
tail -f D:/temp/SESSION-20260324-*/main.dft
```

## Trace format

A method call produces an entry group and an exit group:

```
SI;alice-session-01
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
CD;2
TS;1711234567890
CL;42
TI;17
AR;{"object_id":100,"class":"java.lang.Object[]","value":[3]}
RT;VALUE
RE;{"object_id":101,"class":"java.util.ArrayList","value":[{"object_id":102,"class":"com.example.Book","value":{"title":"Foundation","year":1951}}]}
TN;http-nio-8080-exec-3
TE;1711234567923
```

| Tag | Meaning |
|-----|---------|
| `SI` | Session ID (absent if no resolver configured) |
| `MS` | Method signature: `package::Class.method(params) -> return [modifiers]` |
| `TN` | Thread name |
| `CD` | Call depth (0 = top-level, increments with nesting) |
| `TS` | Timestamp at method entry (epoch ms) |
| `CL` | Source line number of the call site |
| `TI` | `this` instance — object ID (default) or full JSON (`expand_this=true`) |
| `AR` | Arguments as JSON with object identity envelopes |
| `RT` | Return type: `VOID`, `VALUE`, or `EXCEPTION` |
| `RE` | Return/exception value as JSON |
| `TE` | Timestamp at method exit (epoch ms) |

### Object identity envelopes

Arguments and return values are wrapped with identity metadata:

```json
{
  "object_id": 42,
  "class": "com.example.Person",
  "value": { "name": "John", "age": 30 }
}
```

The same Java object always gets the same `object_id`. When you see
`object_id: 42` in the arguments of `validate(person)` and again in
`save(person)`, that's the same instance. If `value` changed between the
two calls, something mutated it — and the trace tells you what.

## Configuration

All options are documented in the [reference config file](deepagent.cfg).

| Property | What it does |
|----------|-------------|
| `session_dump_location` | Directory where trace output is written |
| `matchers_include` | Regex patterns selecting classes to instrument |
| `matchers_exclude` | Regex patterns excluding classes (overrides include) |
| `expand_this` | `false` (default): record object ID only. `true`: serialize full `this` |
| `serialize_values` | `false`: skip serialization — structural-only mode |
| `session_resolver` | Session tracking resolver name (`config`, `spring-session`) |
| `jpa_proxy_resolver` | JPA proxy unwrapping resolver name (`hibernate`) |

Config values can be overridden inline — inline values take precedence over
the file:

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
```

## Session tracking

To tag traces with a session or correlation key, configure a
`SessionIdResolver`:

```properties
# Fixed ID (standalone apps, manual debugging):
session_resolver=config
session_id=my-debug-run-01

# HTTP session tracking (Spring Boot):
session_resolver=spring-session
```

The [demo-spring-boot](demos/demo-spring-boot/) module includes a working
`spring-session` resolver. See
[SESSION-RESOLVER-SPI.md](spi/session-resolver-api/SESSION-RESOLVER-SPI.md)
for writing custom resolvers.

## JPA proxy unwrapping

```properties
jpa_proxy_resolver=hibernate
```

Unwraps Hibernate lazy-loading proxies and collection wrappers before
serialization. The resolver JAR goes on the application classpath (uses
reflection, no compile-time Hibernate dependency). See
[JPA-PROXY-RESOLVER-SPI.md](spi/jpa-proxy-resolver-api/JPA-PROXY-RESOLVER-SPI.md).

## SPI JARs and the classpath

The agent JAR is self-contained (ByteBuddy, Jackson, codec are shaded in).
SPI resolver JARs go on the **application classpath** — they need access to
framework classes that the agent cannot bundle.

**Spring Boot** — add SPI JARs as Maven dependencies.

**Non-Spring-Boot** — set the classpath explicitly:

```bash
java -javaagent:deepflow-agent.jar="config=deepagent.cfg" \
     -cp "your-app.jar;session-resolver-config.jar;jpa-proxy-resolver-hibernate.jar" \
     com.example.MainClass
```

(On Linux/Mac use `:` instead of `;` as classpath separator.)

## Spring Boot demo

A working example with session tracking, JPA proxy resolution, and an
automated test script:

```bash
cd deepflow-agent
mvn clean install
cd demos/demo-spring-boot
bash test-run.sh
```

The script starts the app, exercises the API with two users in separate HTTP
sessions, shuts down, and prints the collected traces. See the
[demo README](demos/demo-spring-boot/README.md).

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
  demos/
    demo-spring-boot/               Working Spring Boot example
  server/
    record-collector-server/        Netty HTTP server (receives binary records)
```
