# Arachna Trace JVM Agent

The Java bytecode-instrumentation agent — the JVM implementation of
the Arachna Trace agent contract. Captures method arguments, return
values, exceptions, object identity, and object mutations from a
target JVM without any code changes.

For solution-level context (what Arachna Trace is and why), see the
[repo root README](../../README.md). For the language-neutral wire-format
contract that any Arachna Trace agent must implement, see
[../../spec/SPEC.md](../../spec/SPEC.md). For agent-generic concepts
that apply to any future non-JVM agent (mutation detection, request-id
propagation, truncation contract), see
[../docs/](../docs/).

## Build

```bash
cd arachna-trace-agents/jvm
mvn clean install
```

Produces the shaded fat JAR at `core/agent/target/arachna-trace-agent.jar`. The
JAR is self-contained (ByteBuddy, Jackson, codec, serializer are all shaded
in). SPI resolver JARs are **not** bundled — they live on the application
classpath so they can access framework classes (Hibernate, Spring session).

## Configure and attach

Minimal `arachna-agent.cfg`:

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

Attach via `-javaagent`:

```bash
java -javaagent:path/to/arachna-trace-agent.jar="config=path/to/arachna-agent.cfg" \
     -jar your-app.jar
```

Spring Boot via the Maven plugin:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/arachna-trace-agent.jar=config=./arachna-agent.cfg"
```

Inline config overrides file values:

```bash
java -javaagent:agent.jar="config=arachna-agent.cfg&serialize_values=false" -jar app.jar
```

For all configuration options, see
[docs/reference/agent-config.md](docs/reference/agent-config.md). For SPI resolver setup, the
Spring Boot demo, and reading traces, see
[docs/getting-started.md](docs/getting-started.md).

## Trace format

Output goes to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/` with one
`.dft` file per thread. A fragment looks like this:

```
VR;1.3
TS;1730412345678
SI;alice-session-01
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
RI;5
CL;42
TI;17
AR;[3]
TE;1730412345712
RT;VALUE
RE;[{"object_id":101,"class":"java.util.ArrayList","value":[...]}]
```

| Tag | Meaning |
|-----|---------|
| `VR` | Wire-format version (`<major>.<minor>`) |
| `TS` | Timestamp at entry (milliseconds since Unix epoch) |
| `SI` | Session ID (omitted if absent) |
| `MS` | Method signature |
| `TN` | Thread name |
| `RI` | Request ID (groups all calls in one request) |
| `CL` | Caller line number |
| `CI` / `PI` | Call ID / parent call ID (UUIDs) |
| `TI` | This instance (object ID or full JSON) |
| `AR` | Arguments as JSON |
| `TE` | Timestamp at exit |
| `RT` | Return type: `VOID`, `VALUE`, or `EXCEPTION` |
| `RE` | Return/exception value as JSON |
| `AX` | Arguments at exit (for mutation detection) |

The full normative tag specification is in [../../spec/TAGS.md](../../spec/TAGS.md).

## Documentation

JVM-specific docs in [docs/](docs/):

- [Getting Started](docs/getting-started.md) -- build, attach, configure
- [Agent Configuration](docs/reference/agent-config.md) -- all options for the JVM agent
- [Performance](docs/reference/performance.md) -- measured agent overhead per traced call, with and without value serialization
- [Architecture](docs/architecture.md) -- JVM agent data flow and modules

Generic any-agent docs in [../docs/](../docs/):

- [Concepts](../docs/concepts.md) -- terminology
- [Mutation Detection](../docs/mutation-detection.md)
- [Request ID propagation](docs/request-id.md)
- [Truncation contract](../docs/truncation.md)
- [Serialize modes](../docs/serialize-modes.md)

Server-side docs in [../../arachna-trace-infra/docs/](../../arachna-trace-infra/docs/):

- [Deployment Modes](../../arachna-trace-infra/docs/reference/deployment-modes.md) -- local, embedded, distributed

Wire-format contract in [../../spec/](../../spec/):

- [SPEC.md](../../spec/SPEC.md) -- top-level overview
- [TAGS.md](../../spec/TAGS.md) -- rendered tag-line specification

For contributing, see [../../CONTRIBUTING.md](../../CONTRIBUTING.md) — extension
points for IDE plugins, embedded-DuckDB mode, and non-Java agents are
described there.

## License

Apache License 2.0. See [LICENSE](../../LICENSE) at the repository root.

## Project structure

```
arachna-trace-agents/jvm/
  arachna-agent.cfg                 Reference config (all options documented)
  core/
    agent/                          Bytecode instrumentation (entry point)
    serializer/                     Buffer, drainer, file/HTTP destinations
```

This reactor contains only the JVM-specific producer code. Everything
else lives in sibling top-level reactors:

| Reactor | What it holds |
|---|---|
| [`../../arachna-trace-shared/`](../../arachna-trace-shared/) | Language-neutral codec, renderer, AgentRun, binary wire types, and the SPI api interfaces. |
| [`../../arachna-trace-jvm-extensions/`](../../arachna-trace-jvm-extensions/) | Reference SPI implementations for the JVM ecosystem (`session-resolver-config`, `session-resolver-spring`, `jpa-proxy-resolver-hibernate`). Each is a self-contained single-class plugin JAR; users either drop them onto the application classpath or copy them as templates for their own. |
| [`../../arachna-trace-infra/`](../../arachna-trace-infra/) | Server-side pipeline — collector, processor, query, ClickHouse schema. |
| [`../../arachna-trace-demos/jvm/`](../../arachna-trace-demos/jvm/) | Sample apps with the agent attached. |
