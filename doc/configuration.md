# Configuration Reference

All configuration is provided via `deepagent.cfg`, a properties file with
`key=value` lines. Lines starting with `#` are comments.

Config is passed to the agent via the `-javaagent` flag:

```bash
java -javaagent:deepflow-agent.jar="config=path/to/deepagent.cfg" -jar app.jar
```

Inline key-value pairs (separated by `&`) override file values:

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
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

See [Serialize Modes](features/serialize-modes.md).

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

See [Truncation](features/truncation.md).

```properties
max_value_size=0
```

### emit_tags

Controls which trace record tags are emitted. Comma-separated list. `MS`
(method signature), `TS` (entry timestamp), and `TE` (exit timestamp) are
always emitted regardless of this setting.

When a tag is disabled, the agent skips both serialization and output -- no
runtime cost for disabled tags.

Available tags: `SI`, `TN`, `RI`, `TS`, `CL`, `TI`, `AR`, `RT`, `RE`, `TE`, `AX`.

See [Trace Format](trace-format.md) for tag descriptions.

```properties
# Default (no AX)
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE

# With mutation detection
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,AX

# Minimal structural trace
emit_tags=TN,RI,TS,CL,TE
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

See [Session Resolver SPI](spi/session-resolver.md).

```properties
session_resolver=config
```

### session_id

Custom session ID used by the `config` resolver. Published as system property
`deepflow.session_id` at startup.

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

See [JPA Proxy Resolver SPI](spi/jpa-proxy-resolver.md).

```properties
jpa_proxy_resolver=hibernate
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
