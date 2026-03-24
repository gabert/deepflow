# Session ID Resolver SPI

The `SessionIdResolver` SPI allows framework-specific code to inject a logical
session ID into every trace record. This enables grouping and filtering traces
by HTTP session, request ID, debug run, or any other correlation key — without
coupling the agent to any particular framework.

## Interface

```java
package com.github.gabert.deepflow.agent.session;

public interface SessionIdResolver {
    String name();      // unique name used for selection via config
    String resolve();   // return session ID for the calling thread, or null
}
```

| Method      | Contract                                                     |
|-------------|--------------------------------------------------------------|
| `name()`    | A short, unique identifier (e.g. `"config"`, `"spring-session"`). The agent matches this against the `session_resolver` config property. |
| `resolve()` | Return the current session/request ID for the calling thread, or `null` if no session context is available. Called on **every** instrumented method entry and exit, so it must be fast — typically a single `ThreadLocal.get()`. |

Implementations must be **stateless** and **thread-safe**.

## Configuration

Enable a resolver by setting `session_resolver` in `deepagent.cfg`:

```properties
session_resolver=config
```

The value must match the `name()` of exactly one resolver on the classpath.

## How the agent loads the resolver

Loading is **lazy** — no SPI lookup happens until the first instrumented method
is entered. This ensures the application's classloaders are fully initialized
(important for Spring Boot and other container-managed environments).

1. Read `session_resolver` from agent config.
2. If not set → use the built-in no-op resolver (returns `null`). No SPI
   lookup is performed.
3. If set → use `ServiceLoader` with the current thread's context classloader
   (falling back to the system classloader) to find all
   `SessionIdResolver` providers.
4. Select the provider whose `name()` matches the config value.
5. If no match → print a warning to `stderr` and fall back to the no-op
   resolver.

## How session ID flows into traces

```
SessionIdResolver.resolve()
  → DeepFlowAdvice (on every method entry and exit)
    → RecordWriter (embedded in METHOD_START and METHOD_END binary payloads)
      → RecordRenderer (rendered as SI;<session_id> text line, only when non-null)
        → .dft file output
```

When the resolver returns `null`, no `SI;` line appears in the output and the
`sid_len` field in the binary payload is 0.

See [RECORD-FORMAT.md](../../core/record-format/RECORD-FORMAT.md) for the binary
layout of the session ID field.

## Available implementations

### config

| Property | Value |
|----------|-------|
| Name     | `config` |
| Module   | `session-resolver-config` |
| Behavior | Reads `System.getProperty("deepflow.session_id")`. |

The agent publishes the `session_id` config property as the system property
`deepflow.session_id` at startup, so this resolver returns whatever value was
set in `deepagent.cfg`:

```properties
session_resolver=config
session_id=my-debug-run-01
```

Useful for standalone applications or batch jobs where a single fixed session
ID is sufficient.

## Example implementations

### spring-session

| Property | Value |
|----------|-------|
| Name     | `spring-session` |
| Module   | `demo-spring-boot` |
| Behavior | Reads the HTTP session ID from a `ThreadLocal` set by `SessionIdFilter`. |

`SessionIdFilter` is a standard Servlet `Filter` that captures
`HttpServletRequest.getSession().getId()` into a `ThreadLocal` on each request
and clears it in a `finally` block. The resolver simply reads that
`ThreadLocal`.

```properties
session_resolver=spring-session
```

This implementation lives in the Spring Boot demo module and serves as a
reference for integrating with web frameworks.

## Writing a custom resolver

1. Create a class implementing `SessionIdResolver`.
2. Register it via the standard Java ServiceLoader mechanism — create the file:
   ```
   META-INF/services/com.github.gabert.deepflow.agent.session.SessionIdResolver
   ```
   containing the fully qualified class name of your implementation.
3. Place the JAR on the application classpath (not inside the agent JAR).
4. Set `session_resolver=<your-name>` in `deepagent.cfg`.

**Example — resolver that reads a correlation ID from an MDC:**

```java
public class MdcSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "mdc";
    }

    @Override
    public String resolve() {
        return org.slf4j.MDC.get("correlationId");
    }
}
```

## Default behavior summary

| Scenario | Behavior |
|----------|----------|
| `session_resolver` not configured | No-op (no `SI;` in output, no SPI lookup) |
| `session_resolver=config` without `session_id` | `resolve()` returns `null` — no `SI;` in output |
| `session_resolver=spring-session` outside HTTP request | `resolve()` returns `null` — no `SI;` in output |
| Named resolver not found on classpath | Warning to stderr, falls back to no-op |
