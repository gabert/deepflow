# Session ID Resolver SPI

The `SessionIdResolver` SPI injects a logical session ID into every trace
record. This enables grouping and filtering traces by HTTP session, request
ID, debug run, or any other correlation key.

## Interface

```java
package com.github.gabert.arachna.trace.spi.session;

import java.util.Map;

public interface SessionIdResolver {
    String name();
    default void init(Map<String, String> config) {}
    String resolve();
}
```

| Method | Contract |
|--------|----------|
| `name()` | Short unique identifier (e.g. `"config"`, `"spring-session"`). Matched against `session_resolver` config. |
| `init(config)` | Called once after the resolver is selected, before any `resolve()` call. Implementations that need configuration (e.g. a static session ID from the agent config) read it from this map. Default no-op for resolvers that don't need it. |
| `resolve()` | Return current session ID for the calling thread, or `null`. Called on every method entry and exit -- must be fast (typically `ThreadLocal.get()`). |

`resolve()` must be thread-safe. `init` runs once on a single thread
before any `resolve` call, so it does not need to be.

## Configuration

```properties
session_resolver=config
```

The value must match the `name()` of exactly one resolver on the classpath.
If not set, no session tracking occurs and no SPI lookup is performed.

## Loading behavior

Loading is **lazy** -- no SPI lookup until the first instrumented method
entry. This ensures application classloaders are initialized (important for
Spring Boot).

1. Read `session_resolver` from config
2. If not set: use built-in no-op resolver (returns `null`)
3. If set: use `ServiceLoader` with context classloader to find all providers
4. Select the provider whose `name()` matches
5. If no match: warning to stderr, fall back to no-op

## How session ID flows into traces

```
SessionIdResolver.resolve()
  -> ArachnaTraceAdvice (every entry and exit)
    -> RecordWriter (in METHOD_START and METHOD_END binary payloads)
      -> RecordRenderer (rendered as SI;<session_id>, only when non-null)
        -> .dft file output
```

When `resolve()` returns `null`, no `SI;` line appears and `sid_len` is 0
in the binary payload.

## Built-in: config — the universal fallback

| Property | Value |
|----------|-------|
| Name | `config` |
| Module | `session-resolver-config` |

The `config` resolver is **framework-agnostic**: it reads a string
out of the agent config and returns it on every `resolve()` call.
No HTTP, no servlet, no framework integration involved.

```properties
session_resolver=config
session_id=my-debug-run-01
```

That makes it the **always-works baseline**. Any JVM, any framework
or none, can use Arachna Trace on day one — you trade automatic
per-request session detection for an explicit grouping label that
you choose. For many use cases the explicit label is what you
actually wanted anyway:

| Scenario | What you set `session_id` to |
|---|---|
| Framework with no shipped resolver yet (Quarkus, Micronaut, Vert.x, Tomcat-standalone, embedded Jetty, ...) | A stable identifier per logical "session" you can supply yourself — typically per JVM run, per tenant, or per scheduled job. |
| Batch job / scheduled import / ETL pipeline / CLI tool | The job's logical name + run timestamp, e.g. `nightly-import-2026-05-15`. Every captured call ends up grouped under that label in the UI. |
| Message-queue consumer / worker process | Per-process identifier; messages handled by that process land in the same session. Pair with `agent_run_id` (automatic) for restart attribution. |
| Per-deploy tagging | Your build identifier, e.g. `session_id=$BUILD_ID-$DEPLOY_AT`. Every trace produced by that deploy is grouped — useful for "did this bug appear after the 14:30 deploy?". |
| Single-tenant deployment | The tenant name. One JVM = one tenant = one session label. |
| Debugging a specific reproduction | A descriptive label, e.g. `session_id=debug-issue-1234-attempt-3`. Everything from that JVM run lands in a session named after the bug you're chasing. |

In `init(config)` the resolver reads the `session_id` key once and
caches it. Every `resolve()` call returns the cached value. No
system property, no thread-local, no per-request work. If
`session_id` is unset, `resolve()` returns `null` and no `SI;`
line appears.

If a framework-specific resolver later ships for your stack and
suits your use case better, switching is one config line — every
recorded trace continues to validate against the same wire format.

## Built-in: spring-session

| Property | Value |
|----------|-------|
| Name | `spring-session` |
| Module | `session-resolver-spring` (under `arachna-trace-jvm-extensions/`) |

Three classes ship in the JAR:

- `SpringSessionIdResolver` — reads from a thread-local
- `SessionIdHolder` — the thread-local
- `SessionIdFilter` — a Jakarta Servlet `Filter` that captures
  `request.getSession().getId()` into the thread-local on each request

The filter is intentionally **not** annotated `@Component` — register
it explicitly in your app (typically `@Bean SessionIdFilter ...` on
your `@SpringBootApplication`-annotated class). Forgetting this is a
common pitfall — see
[SPI wiring — common failures, case C](spi-wiring.md#c--filter--setup-class-is-referenced-but-not-registered).

```properties
session_resolver=spring-session
```

## Writing a custom resolver

1. Implement `SessionIdResolver`
2. Register via ServiceLoader:
   ```
   META-INF/services/com.github.gabert.arachna.trace.spi.session.SessionIdResolver
   ```
3. Place JAR on the application classpath (not in the agent JAR)
4. Set `session_resolver=<your-name>` in config

For the complete wiring chain — what each file looks like, how
ServiceLoader picks impls, and how to diagnose when it doesn't fire —
see [SPI wiring](spi-wiring.md). Reading that once will save hours of
"why isn't my resolver being used?" later.

**Example -- MDC correlation ID:**

```java
public class MdcSessionIdResolver implements SessionIdResolver {
    @Override public String name() { return "mdc"; }
    // No init() needed: nothing to read from the config map.
    @Override public String resolve() {
        return org.slf4j.MDC.get("correlationId");
    }
}
```

## Behavior summary

| Scenario | Behavior |
|----------|----------|
| `session_resolver` not set | No-op, no `SI;` in output |
| `session_resolver=config` without `session_id` | `resolve()` returns `null`, no `SI;` |
| `session_resolver=spring-session` outside HTTP request | `resolve()` returns `null`, no `SI;` |
| Named resolver not found | Warning to stderr, falls back to no-op |
