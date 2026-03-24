# Deepflow Agent

Deepflow is a Java application tracing tool. Attach it to any JVM application
and it captures every method call — arguments, return values, exceptions, object
identity, and mutations — as structured trace files. No code changes required.

## Quick start

### 1. Build

```bash
cd deepflow-agent
mvn clean install
```

This produces `core/agent/target/deepflow-agent.jar`.

### 2. Create a config file

Create `deepagent.cfg` (or copy the [reference config](deepagent.cfg) which
documents all options with examples):

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

### 4. Read the output

Traces are written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/`
with one `.dft` file per thread. Files are flushed after each record, so you
can read them while the app is running.

```bash
# list session directories
ls D:/temp/SESSION-*/

# tail a thread's trace in real time
tail -f D:/temp/SESSION-20260324-*/main.dft
```

## Reading trace files

Each line in a `.dft` file is a tag-value pair separated by `;`. A method
call produces an entry group and an exit group:

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
| `SI` | Session ID (from resolver SPI — absent if no resolver configured) |
| `MS` | Method signature: `package::Class.method(params) -> return [modifiers]` |
| `TN` | Thread name |
| `CD` | Call depth (0 = top-level, 1 = one nested, ...) |
| `TS` | Timestamp at method entry (epoch ms) |
| `CL` | Source line number of the call site |
| `TI` | `this` instance — object ID (default) or full JSON (if `expand_this=true`) |
| `AR` | Arguments as JSON (CBOR-decoded with object identity envelopes) |
| `RT` | Return type: `VOID`, `VALUE`, or `EXCEPTION` |
| `RE` | Return/exception value as JSON |
| `TE` | Timestamp at method exit (epoch ms) |

### Object identity in JSON

Arguments and return values are wrapped in envelopes that track object
identity:

```json
{
  "object_id": 42,
  "class": "com.example.Person",
  "value": { "name": "John", "age": 30 }
}
```

The `object_id` is a stable unique ID for the lifetime of the object. By
comparing the same `object_id` at method entry vs exit, you can detect which
arguments were mutated. See [CODEC.md](core/codec/CODEC.md) for the full
envelope format.

## Configuration

All options are documented in the [reference config file](deepagent.cfg) with
examples. The key settings:

| Property | What it does |
|----------|-------------|
| `session_dump_location` | Directory where trace output is written |
| `matchers_include` | Regex patterns selecting classes to instrument |
| `matchers_exclude` | Regex patterns excluding classes (overrides include) |
| `expand_this` | `false` (default): record object ID only. `true`: serialize full `this` |
| `serialize_values` | `false`: skip serialization, record only call graph (dead code detection mode) |
| `session_resolver` | Enable session tracking SPI (see below) |
| `jpa_proxy_resolver` | Enable JPA proxy unwrapping SPI (see below) |

Config values can be overridden inline on the command line — inline values
take precedence over the file:

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
```

## Session tracking

By default, traces have no session context. To group traces by HTTP session,
request ID, or any other correlation key, you need a `SessionIdResolver` SPI
implementation on the application classpath.

### Built-in: fixed session ID

For standalone apps or debugging, use the `config` resolver which reads a
fixed value from the config:

```properties
session_resolver=config
session_id=my-debug-run-01
```

The resolver JAR (`session-resolver-config`) must be on the classpath.

### Spring Boot: HTTP session tracking

The [demo-spring-boot](demos/demo-spring-boot/) module includes a
`spring-session` resolver that captures `HttpServletRequest.getSession().getId()`
via a servlet filter and ThreadLocal. Use it as a template for your own app.

See [SESSION-RESOLVER-SPI.md](spi/session-resolver-api/SESSION-RESOLVER-SPI.md)
for the full guide on writing custom resolvers.

## JPA proxy unwrapping

Hibernate substitutes proxy objects for lazy-loaded entities and wraps
collections (`PersistentBag`, `PersistentSet`). Without a resolver, these
appear as `<proxy>` in trace output. To capture their real state:

```properties
jpa_proxy_resolver=hibernate
```

The resolver JAR (`jpa-proxy-resolver-hibernate`) must be on the application
classpath. It uses reflection — no compile-time Hibernate dependency.

See [JPA-PROXY-RESOLVER-SPI.md](spi/jpa-proxy-resolver-api/JPA-PROXY-RESOLVER-SPI.md)
for details and custom implementations.

## SPI JARs and the classpath

The agent JAR is self-contained (ByteBuddy, Jackson, codec are all shaded in).
But SPI resolver JARs (`session-resolver-config`, `jpa-proxy-resolver-hibernate`,
or your own) must go on the **application classpath**, not inside the agent.
They need access to framework classes (e.g. Hibernate, Spring session) that
the agent cannot bundle.

**Spring Boot** apps typically get this automatically — add the SPI JARs as
Maven dependencies and Spring Boot packages them.

**Non-Spring-Boot** apps need explicit classpath setup:

```bash
java -javaagent:deepflow-agent.jar="config=deepagent.cfg" \
     -cp "your-app.jar;session-resolver-config.jar;jpa-proxy-resolver-hibernate.jar" \
     com.example.MainClass
```

(On Linux/Mac use `:` instead of `;` as classpath separator.)

## Dead code detection mode

Set `serialize_values=false` to skip all CBOR serialization. The agent records
only the call graph — which methods were called, at what depth, by which thread.
This is significantly cheaper and useful for identifying dead code paths.

## Spring Boot demo

The `demos/demo-spring-boot/` module is a working example with session tracking,
JPA proxy resolution, and an automated test script:

```bash
cd deepflow-agent
mvn clean install
cd demos/demo-spring-boot
bash test-run.sh
```

The script starts the app, exercises the API with two users (Alice and Bob) in
separate HTTP sessions, shuts down, and prints the collected traces. See the
[demo README](demos/demo-spring-boot/README.md) for details.

## Project structure

```
deepflow-agent/
  deepagent.cfg                     Reference config (all options documented)
  core/
    agent/                          Bytecode instrumentation (entry point)
    codec/                          CBOR serialization with identity envelopes
    record-format/                  Binary wire format specification
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
