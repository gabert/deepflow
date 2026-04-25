# Session ID Resolver SPI

The `SessionIdResolver` SPI injects a logical session ID into every trace
record. This enables grouping and filtering traces by HTTP session, request
ID, debug run, or any other correlation key.

## Interface

```java
package com.github.gabert.deepflow.agent.session;

public interface SessionIdResolver {
    String name();      // unique name for selection via config
    String resolve();   // return session ID for current thread, or null
}
```

| Method | Contract |
|--------|----------|
| `name()` | Short unique identifier (e.g. `"config"`, `"spring-session"`). Matched against `session_resolver` config. |
| `resolve()` | Return current session ID for the calling thread, or `null`. Called on every method entry and exit -- must be fast (typically `ThreadLocal.get()`). |

Implementations must be stateless and thread-safe.

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
  -> DeepFlowAdvice (every entry and exit)
    -> RecordWriter (in METHOD_START and METHOD_END binary payloads)
      -> RecordRenderer (rendered as SI;<session_id>, only when non-null)
        -> .dft file output
```

When `resolve()` returns `null`, no `SI;` line appears and `sid_len` is 0
in the binary payload.

## Built-in: config

| Property | Value |
|----------|-------|
| Name | `config` |
| Module | `session-resolver-config` |

Reads `System.getProperty("deepflow.session_id")`. The agent publishes
the `session_id` config value as this system property at startup.

```properties
session_resolver=config
session_id=my-debug-run-01
```

## Example: spring-session

| Property | Value |
|----------|-------|
| Name | `spring-session` |
| Module | `demo-spring-boot` |

`SessionIdFilter` (a Servlet `Filter`) captures
`request.getSession().getId()` into a `ThreadLocal` on each request.
The resolver reads that `ThreadLocal`.

```properties
session_resolver=spring-session
```

## Writing a custom resolver

1. Implement `SessionIdResolver`
2. Register via ServiceLoader:
   ```
   META-INF/services/com.github.gabert.deepflow.agent.session.SessionIdResolver
   ```
3. Place JAR on the application classpath (not in the agent JAR)
4. Set `session_resolver=<your-name>` in config

**Example -- MDC correlation ID:**

```java
public class MdcSessionIdResolver implements SessionIdResolver {
    @Override public String name() { return "mdc"; }
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
