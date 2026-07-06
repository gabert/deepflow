# Agent Configuration

The Java agent's runtime knobs. Read via `-javaagent` from
`arachna-agent.cfg` (a properties file with `key=value` lines;
`#`-prefixed lines are comments).

Arachna Trace has four components, each with its own config:

- **Agent** — this doc.
- **Collector server** — [collector-config.md](../../../../arachna-trace-infra/docs/reference/collector-config.md).
- **Processor server** — [processor-config.md](../../../../arachna-trace-infra/docs/reference/processor-config.md).
- **Query server** — [query-server-config.md](../../../../arachna-trace-infra/docs/reference/query-server-config.md).

For the cross-component size-limit alignment contract, see
[deployment-modes.md](../../../../arachna-trace-infra/docs/reference/deployment-modes.md#size-limits-across-the-pipeline--alignment-contract).

Config is passed to the agent via the `-javaagent` flag:

```bash
java -javaagent:arachna-trace-agent.jar="config=path/to/arachna-agent.cfg" -jar app.jar
```

Inline key-value pairs (separated by `&`) override file values:

```bash
java -javaagent:agent.jar="config=arachna-agent.cfg&serialize_values=false" -jar app.jar
```

## Options

### session_dump_location

Directory where trace output is written. Each agent run creates a subdirectory
`SESSION-<yyyyMMdd-HHmmss>/` containing one `.dft` file per thread.

```properties
session_dump_location=D:\temp
```

### destination

Output sink type.

| Value | Description |
|-------|-------------|
| `file` | Render to human-readable `.dft` files (default) |
| `http` | Send raw binary records to a collector server |

```properties
destination=file
```

### matchers_include

Regex patterns matched against fully-qualified class names. Comma-separated,
OR logic. Only matched classes are instrumented.

```properties
# All classes under com.example
matchers_include=com\.example\..*

# Multiple packages
matchers_include=com\.example\.service\..*,com\.example\.repository\..*

# Classes ending with Service, Controller, or Repository
matchers_include=com\.example\..*(Controller|Service|Repository)$
```

### matchers_exclude

Same syntax as `matchers_include`. Removes classes from the include set.

```properties
matchers_exclude=com\.example\.util\..*
```

### serialize_values

Controls serialization of arguments, return values, and exceptions.

| Value | Description |
|-------|-------------|
| `true` | Full CBOR serialization (default) |
| `false` | Skip serialization, record only call graph and timestamps |

When `false`, only structural records are emitted (MS, TN, RI, TS, CL, TE).
Use this for dead code detection.

See [Serialize Modes](../../../docs/serialize-modes.md).

```properties
serialize_values=true
```

### expand_this

Controls how the `this` instance is captured.

| Value | Description |
|-------|-------------|
| `false` | TI shows object reference ID only (default, compact) |
| `true` | TI shows full CBOR-serialized object (verbose) |

```properties
expand_this=false
```

### max_value_size

Truncation cap for serialized values, in bytes. Applies to arguments, return
values, exceptions, and `this` instance payloads individually.

When a CBOR-encoded value exceeds this limit, the payload is replaced with
a truncation marker:

```json
{"__truncated": true, "original_size": 12345}
```

| Value | Description |
|-------|-------------|
| `0` | No truncation (default) |
| `>0` | Maximum CBOR payload size in bytes |

Recommended starting points: `8192` (8 KB) or `32768` (32 KB).

See [Truncation](../../../docs/truncation.md).

```properties
max_value_size=0
```

### buffer_max_records_per_thread

Per-thread cap on the in-memory record queue between traced threads and
the drain thread. `0` (default) means unbounded: memory grows during
bursts and is reclaimed once the drainer catches up. A positive value
protects the host application's heap when the drain side stalls (for
example, the collector is unreachable): a thread whose queue is at the
cap drops new records instead of growing.

Drops are never silent -- each shard logs when it starts dropping and
every 10,000th drop after that, and a shutdown summary reports the
total. A trace with drops is incomplete and says so.

| Value | Description |
|-------|-------------|
| `0` | Unbounded (default) |
| `>0` | Maximum queued records per thread |

```properties
buffer_max_records_per_thread=0
```

### instrumentation_inventory

Path to write the **instrumentation inventory**: one line per method the
agent wove advice into, in the exact signature format traces carry
(`MS` tag / `calls.signature` column). Unset (default) disables it.

This is one half of the *liveness sweep* (see
[AI code audit](../../../docs/ai-code-audit.md)): after exercising the
application — regression suite, staging traffic — diff the inventory
against the signatures actually observed
(`GET /api/analysis/observed-signatures?session_id=...`). Methods that
are instrumented but never executed are code that survived a change
(often an AI-generated refactor) without ever running.

The format of both lists is identical by construction (pinned by a
format-parity test), so the diff is a plain line comparison:

```bash
sort instrumented-methods.txt > inventory.sorted
curl -s 'http://localhost:8082/api/analysis/observed-signatures?session_id=SID'   | jq -r '.[].signature' | sort > observed.sorted
comm -23 inventory.sorted observed.sorted   # instrumented, never called
```

```properties
#instrumentation_inventory=D:/temp/instrumented-methods.txt
```

### emit_tags

Controls which trace record tags are emitted. Comma-separated list.
`MS` (method signature) is always emitted regardless of this setting --
every call must be identifiable by signature. Every other tag, including
`TS` and `TE`, is filtered exactly as listed.

When a tag is disabled, the agent skips both serialization and output -- no
runtime cost for disabled tags.

Available tags: `SI`, `TN`, `RI`, `TS`, `CL`, `CI`, `PI`, `TI`, `AR`, `RT`, `RE`, `TE`, `AX`, `SQ`.

`SQ` carries an agent-observed monotonic ordinal per agent run.
It's the canonical ordering primitive — sub-millisecond `ts_in`
ties are disambiguated by `SQ`. Costs 24 bytes per call on the
wire. Drop only if minimising payload size matters more than
stable ordering.

`CI` and `PI` always live on the binary wire regardless of this
setting — the processor uses them to pair MS↔ME and link the call
tree without stack reconstruction. Filtering them out only
suppresses them from the rendered `.dft` text.

Exceptions are likewise always recorded on the wire, even when `RT`
and `RE` are filtered — an exceptional exit must never be stored as
`VOID`. Filtering `RT`/`RE` only skips serializing normal return
values (and suppresses the rendered lines).

See [Trace Format](../../../../spec/TAGS.md) for tag descriptions.

```properties
# Default — matches ConfigLoader.DEFAULT_EMIT_TAGS:
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,SQ

# Mutation detection mode (add AX to see args before and after):
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,SQ,AX

# Default + render call IDs (useful for manual MS↔ME pairing in .dft):
emit_tags=SI,TN,RI,TS,CL,CI,PI,TI,AR,RT,RE,TE,SQ

# Minimal structural trace (no values, just call tree):
emit_tags=TN,RI,TS,CL,TE,SQ
```

### parameter_names

Controls how AR/AX argument keys are encoded.

| Value | Description |
|-------|-------------|
| `true` | Real parameter names (e.g. `{"isbn":"...", "year":1937}`) when the target was compiled with `-parameters` *or* with debug info (`-g`, default in Maven/Gradle); falls back to integer keys per method if neither attribute is available |
| `false` | Skip the resolver entirely. Every method emits integer keys (`{"0":"...", "1":1937}`); no class bytes read, no cache entries created |

Default: `true`.

When `true`, names are resolved per method and cached per-Class.
Methods on stripped jars fall back to integer keys and are not
cached. See [Argument Names](argument-names.md) for the resolution
order and fallback rules.

```properties
parameter_names=true
```

### propagate_request_id

When `true`, the agent instruments `ThreadPoolExecutor.execute()` and
`ForkJoinPool.execute()/submit()` to propagate the request ID from the
submitting thread to the executing thread. This ensures `@Async` calls and
`CompletableFuture` tasks share the same request ID as the originating
request.

Default: `true`.

```properties
propagate_request_id=true
```

### session_resolver

Selects which `SessionIdResolver` SPI implementation to activate. The value
must match the `name()` of a resolver on the classpath.

| Value | Description |
|-------|-------------|
| `config` | Reads `session_id` from agent config |
| `spring-session` | Reads HTTP session ID from ThreadLocal |
| (not set) | No session tracking |

See [Session Resolver SPI](session-resolver.md).

```properties
session_resolver=config
```

### session_id

Static session ID used by the `config` resolver. The resolver reads this
value from the agent config map at startup (via its `init(Map)` SPI hook)
and returns it from every `resolve()` call.

```properties
session_id=my-debug-run-01
```

### jpa_proxy_resolver

Selects which `JpaProxyResolver` SPI implementation to activate. The value
must match the `name()` of a resolver on the classpath.

| Value | Description |
|-------|-------------|
| `hibernate` | Unwraps Hibernate entity proxies and collection wrappers |
| (not set) | Proxies appear as `<proxy>` in output |

See [JPA Proxy Resolver SPI](jpa-proxy-resolver.md).

```properties
jpa_proxy_resolver=hibernate
```

### code_version

Application build / version identifier. Carried as transport-layer
metadata (HTTP / Kafka header for `destination=http`, `run.json`
sidecar for `destination=file`) so traces can be attributed to a
specific deploy. Typical values: a git SHA, a release tag, a CI
build number.

Optional. If absent, the `agent_runs.code_version` ClickHouse
column ends up empty.

```properties
code_version=git-abc12345
```

### env

Environment label (`prod`, `staging`, `dev`, `local`, …). Free-form
string carried as transport-layer metadata alongside `code_version`.
Useful for filtering traces by deployment tier when multiple
environments share a trace store.

Optional. If absent, the `agent_runs.env` ClickHouse column ends
up empty.

```properties
env=local
```

### http_server_url

URL of the record-collector-server endpoint. Only relevant when
`destination=http`.

```properties
http_server_url=http://localhost:8099/records
```

### http_flush_threshold

Batching threshold in bytes for HTTP destination. Records are buffered and
sent in a single POST when the buffer reaches this size.

Default: `65536` (64 KB).

```properties
http_flush_threshold=65536
```

### http_buffer_max_bytes

Cap on bytes retained for retry by the HTTP destination. Unsent records are
kept in memory while the collector is unreachable; when the backlog exceeds
this cap it is dropped (with an error log) so the agent never grows the host
application's heap without bound.

Default: `33554432` (32 MB).

```properties
http_buffer_max_bytes=33554432
```
