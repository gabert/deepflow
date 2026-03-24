# Deepflow Agent

**See exactly what your Java application does at runtime — every method call,
every argument value, every return value, every object mutation. No code changes.
No annotations. Just attach and observe.**

Most debugging tools tell you *where* your code went. Deepflow tells you *what
happened to your data* as it flowed through. When a user reports that their order
total is wrong, a field is unexpectedly null, or a list comes back empty — you
don't add log statements and redeploy. You attach Deepflow, reproduce the
scenario, and read the full story: which method received what, what it returned,
and where the data went wrong.

## What makes Deepflow different

**It captures actual data, not just call spans.** Distributed tracing tools like
OpenTelemetry and Jaeger show you that `BookService.findByAuthor` was called and
took 23ms. Deepflow shows you that it was called with `authorId=3`, returned an
`ArrayList` containing two `Book` objects with titles "Foundation" and "Dune",
and that the caller then passed that list to `formatResponse` which returned it
with the prices zeroed out. That's the difference between knowing *a method was
called* and knowing *what went wrong*.

**It tracks object identity across your entire execution.** Every object gets a
stable unique ID. When the same `Order` object appears as an argument to
`validateOrder`, then `calculateTax`, then `saveOrder` — you see the same
`object_id` in all three calls. If its contents changed between calls, you know
exactly which method mutated it. No guessing, no println debugging.

**It requires zero code changes.** Pure bytecode instrumentation via
`-javaagent`. No annotations to add, no SDK to integrate, no dependencies to
manage in your application. Point it at your packages, run your app, read the
trace.

**It separates traces by thread.** Each thread writes its own trace file. When
debugging concurrent code, you read each thread's file independently — a clean,
linear narrative for each thread, not an interleaved mess.

## When to use Deepflow

### Debugging data errors

*"The API returns the wrong price for this product."*

Attach Deepflow, hit the endpoint, read the trace. You'll see the exact argument
values flowing through your service layer, repository calls, and DTO mappings.
Find the method where the correct value goes in but the wrong value comes out.
No log statements, no breakpoints, no guessing.

### Understanding unfamiliar code

*"I just joined this project and I have no idea how checkout actually works."*

Instrument the relevant packages, trigger a checkout, and read the trace file.
You'll see the complete call tree with actual data — not abstract class diagrams,
but the real execution path with real values. Better than any documentation.

### Debugging concurrent and multi-user issues

*"It works fine with one user but breaks under load."*

Enable session tracking to tag traces by HTTP session. Run your multi-user
scenario. Each user's requests are tagged with their session ID, and each thread
has its own trace file. If two threads touch the same object (same `object_id`),
you can see exactly who modified what and when (timestamps on every entry/exit).

### Finding dead code

*"We think half these service methods are unused but we're afraid to delete them."*

Set `serialize_values=false` — the agent records only the call graph with near-zero
overhead (no serialization). Run your test suite or exercise the app manually.
Any method that doesn't appear in the traces is dead code. More precise than
static analysis because it captures the actual runtime behavior, including
reflection, dynamic dispatch, and framework magic.

### Verifying test coverage — with data

*"Our tests pass but do they actually exercise the edge cases?"*

Line coverage tells you a method was entered. Deepflow tells you *what values*
flowed through it. See whether your tests actually hit the null-handling path,
the empty-list case, or the overflow condition — not just whether the line was
reached.

### Investigating Hibernate and JPA issues

*"The response contains a proxy object instead of real data."*

Enable the JPA proxy resolver (`jpa_proxy_resolver=hibernate`) and Deepflow
unwraps Hibernate lazy-loading proxies and collection wrappers before
serialization. See the actual entity state, not `<proxy>`. Find out whether the
issue is in your query, your transaction boundary, or your DTO mapping.

### Comparing working vs broken behavior

Run the same scenario before and after a code change. Diff the trace files.
See exactly which method call now returns different data, receives different
arguments, or follows a different code path.

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

## Reading trace output

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

Reading this: `BookService.findByAuthor` was called in Alice's session, on thread
`http-nio-8080-exec-3`, at call depth 2, from source line 42. It received one
argument (long value `3`). It returned an `ArrayList` containing a `Book` with
title "Foundation". The call took 33ms (exit timestamp minus entry timestamp).

| Tag | Meaning |
|-----|---------|
| `SI` | Session ID (from resolver SPI — absent if no resolver configured) |
| `MS` | Method signature: `package::Class.method(params) -> return [modifiers]` |
| `TN` | Thread name |
| `CD` | Call depth (0 = top-level, 1 = one nested, ...) |
| `TS` | Timestamp at method entry (epoch ms) |
| `CL` | Source line number of the call site |
| `TI` | `this` instance — object ID (default) or full JSON (if `expand_this=true`) |
| `AR` | Arguments as JSON with object identity envelopes |
| `RT` | Return type: `VOID`, `VALUE`, or `EXCEPTION` |
| `RE` | Return/exception value as JSON |
| `TE` | Timestamp at method exit (epoch ms) |

### Object identity

Arguments and return values are wrapped in envelopes that carry a stable
`object_id`:

```json
{
  "object_id": 42,
  "class": "com.example.Person",
  "value": { "name": "John", "age": 30 }
}
```

The same Java object always has the same `object_id` for its entire lifetime.
When you see `object_id: 42` in the arguments of `validate(person)` and again
in `save(person)`, that's the same object. If the `value` changed between the
two calls, something mutated it.

See [CODEC.md](core/codec/CODEC.md) for the full envelope format.

## Configuration

All options are documented in the [reference config file](deepagent.cfg). The
key settings:

| Property | What it does |
|----------|-------------|
| `session_dump_location` | Directory where trace output is written |
| `matchers_include` | Regex patterns selecting classes to instrument |
| `matchers_exclude` | Regex patterns excluding classes (overrides include) |
| `expand_this` | `false` (default): record object ID only. `true`: serialize full `this` |
| `serialize_values` | `false`: skip serialization — dead code detection mode |
| `session_resolver` | Enable session tracking SPI (see below) |
| `jpa_proxy_resolver` | Enable JPA proxy unwrapping SPI (see below) |

Config values can be overridden inline — inline values take precedence over the
file:

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
```

## Session tracking

To group traces by HTTP session, request ID, or any correlation key, configure
a `SessionIdResolver` SPI.

**Fixed session ID** (standalone apps, manual debugging):

```properties
session_resolver=config
session_id=my-debug-run-01
```

**HTTP session tracking** (Spring Boot):

The [demo-spring-boot](demos/demo-spring-boot/) module includes a
`spring-session` resolver that captures `HttpServletRequest.getSession().getId()`
via a servlet filter. Use it as a template for your own app.

See [SESSION-RESOLVER-SPI.md](spi/session-resolver-api/SESSION-RESOLVER-SPI.md)
for writing custom resolvers.

## JPA proxy unwrapping

Hibernate substitutes proxy objects for lazy-loaded entities and wraps
collections (`PersistentBag`, `PersistentSet`). Without a resolver, these
appear as `<proxy>` in traces. To see the real state:

```properties
jpa_proxy_resolver=hibernate
```

The resolver JAR must be on the application classpath (it uses reflection — no
compile-time Hibernate dependency). See
[JPA-PROXY-RESOLVER-SPI.md](spi/jpa-proxy-resolver-api/JPA-PROXY-RESOLVER-SPI.md).

## SPI JARs and the classpath

The agent JAR is self-contained (ByteBuddy, Jackson, codec are all shaded in).
SPI resolver JARs must go on the **application classpath** — they need access
to framework classes (Hibernate, Spring session) that the agent cannot bundle.

**Spring Boot** — add SPI JARs as Maven dependencies, Spring Boot packages them.

**Non-Spring-Boot** — set the classpath explicitly:

```bash
java -javaagent:deepflow-agent.jar="config=deepagent.cfg" \
     -cp "your-app.jar;session-resolver-config.jar;jpa-proxy-resolver-hibernate.jar" \
     com.example.MainClass
```

(On Linux/Mac use `:` instead of `;` as classpath separator.)

## Spring Boot demo

A working example with session tracking, JPA proxy resolution, and an automated
test script:

```bash
cd deepflow-agent
mvn clean install
cd demos/demo-spring-boot
bash test-run.sh
```

The script starts the app, exercises the API with two users (Alice and Bob) in
separate HTTP sessions, shuts down, and prints the collected traces. See the
[demo README](demos/demo-spring-boot/README.md).

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

## Design principles

- **Never crash the target application.** All agent code runs inside
  `try/catch(Throwable)`. If serialization fails, the agent logs to stderr and
  continues. Your app keeps running.
- **Whitelist-only instrumentation.** Nothing is instrumented unless explicitly
  matched by `matchers_include`. No surprise overhead.
- **Structured, machine-readable output.** Trace files are designed for both
  human reading and automated processing — grep for a method name, parse with
  scripts, or feed to AI analysis tools.
