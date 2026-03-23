# Session Resolver SPI

The agent supports pluggable session ID resolution via Java's
`ServiceLoader` mechanism. A `SessionIdResolver` implementation lets you
inject framework-specific context (e.g. HTTP request ID, Servlet session,
Spring trace ID) into every trace record, so that records can be grouped
by logical request later.

## How it works

1. At agent startup, the `session_resolver` config property is read.
2. If set, the agent uses `ServiceLoader` to find the provider whose
   `name()` matches the configured value.
3. On every instrumented method entry, the agent calls `resolver.resolve()`.
   If the result is non-null, a `SESSION_ID` record is prepended to the
   method entry records.
4. If `session_resolver` is not set, session tracking is disabled (no SPI
   lookup is performed).

Multiple resolver JARs can be on the classpath simultaneously — only the
one matching the configured name is activated. No classpath exclusions needed.

## Interface

```java
package com.github.gabert.deepflow.agent.session;

public interface SessionIdResolver {
    /** Unique name used to select this resolver via config. */
    String name();

    /** Return session ID for the calling thread, or null. */
    String resolve();
}
```

Defined in the `SessionResolverApi` module — a lightweight dependency with
no transitive baggage.

## Available resolvers

| Module | `name()` | Behavior |
|--------|----------|----------|
| `session-resolver-noop` | `"noop"` | Returns null — disables session tracking |
| `session-resolver-config` | `"config"` | Returns `System.getProperty("deepflow.session_id")` |

The agent publishes the `session_id` config value as the system property
`deepflow.session_id` at startup, so the config resolver picks it up
without any coupling to the agent internals.

### Config resolver example

```properties
session_resolver=config
session_id=my-debug-run-01
```

Every method entry record will include `SI;my-debug-run-01`.

## Implementing a custom resolver

### 1. Create a Maven module

```xml
<project>
    <parent>
        <groupId>com.github.gabert</groupId>
        <artifactId>DeepFlow</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>MySessionResolver</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.github.gabert</groupId>
            <artifactId>SessionResolverApi</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</project>
```

### 2. Implement the interface

```java
package com.example;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

public class ServletSessionIdResolver implements SessionIdResolver {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    public static void setRequestId(String id) {
        REQUEST_ID.set(id);
    }

    public static void clearRequestId() {
        REQUEST_ID.remove();
    }

    @Override
    public String name() {
        return "servlet";
    }

    @Override
    public String resolve() {
        return REQUEST_ID.get();
    }
}
```

**Requirements:**
- Must be **thread-safe** — called concurrently from all instrumented threads.
- Must be **fast** — called on every method entry. A single `ThreadLocal.get()`
  is ideal. Avoid I/O, locking, or allocations.
- Return `null` when there is no active session context.

### 3. Register via ServiceLoader

Create the file:

```
src/main/resources/META-INF/services/com.github.gabert.deepflow.agent.session.SessionIdResolver
```

With the fully qualified class name of your implementation:

```
com.example.ServletSessionIdResolver
```

### 4. Configure and run

```properties
session_resolver=servlet
```

```bash
java -javaagent:deepflow-agent.jar=config=deepagent.cfg \
     -cp "your-app.jar;my-session-resolver.jar" \
     com.example.MainClass
```

The agent uses `ClassLoader.getSystemClassLoader()` for ServiceLoader lookup,
so the resolver JAR must be on the system classpath (not in a framework-managed
classloader).

## Example modules

- **`session-resolver-noop`** — minimal reference implementation, returns null
- **`session-resolver-config`** — reads session ID from system property
- **`demo`** module — `DemoSessionIdResolver` returns a fixed test value

Use `session-resolver-noop` as a template for your own resolver.

## Resolution rules

- The agent selects the resolver whose `name()` matches `session_resolver`.
- If `session_resolver` is **not set**, session tracking is disabled (no
  SPI lookup at all).
- If set but **no matching provider** is found, a warning is printed to
  stderr and session tracking is disabled.
- Multiple providers can coexist on the classpath — only the named one
  is used.

## System properties

The agent publishes config values as system properties so that resolver
implementations can read them without coupling to `AgentConfig`:

| Config property | System property | Used by |
|----------------|-----------------|---------|
| `session_id` | `deepflow.session_id` | `session-resolver-config` |

## Output format

When a session ID is present, the binary record stream includes a `SESSION_ID`
(type `0x08`) record before the method entry group. The serializer renders it
as:

```
SI;my-request-id-123
```
