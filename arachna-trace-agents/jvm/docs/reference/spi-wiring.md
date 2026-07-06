# SPI wiring — how the agent finds and activates a resolver

Every Arachna Trace SPI (today: `SessionIdResolver`, `JpaProxyResolver`)
is wired the same way: implement an interface, register the impl class
in a `META-INF/services/` file, drop the JAR on the application
classpath, name the impl in `arachna-agent.cfg`. This document
explains the complete chain, the files involved, and how to diagnose
when it doesn't work.

If you're writing your own resolver: read this once. If you're
debugging "why isn't my resolver being used?" — start here.

---

## The four pieces

A working SPI requires **all four** of these to line up. If any one is
wrong, the agent silently falls back (warning to stderr) or throws a
loud `ServiceConfigurationError`.

```
                       ┌──────────────────────────────────────────────────┐
                       │  1. The interface (the SPI contract)             │
                       │     Lives in arachna-trace-shared/spi/<name>-api/│
                       │     e.g. SessionIdResolver,  JpaProxyResolver    │
                       └──────────────────┬───────────────────────────────┘
                                          │ implements
                                          ▼
                       ┌──────────────────────────────────────────────────┐
                       │  2. The impl class                               │
                       │     Implements the interface; returns its short  │
                       │     identifier from name() — e.g. "spring-       │
                       │     session" or "hibernate". This is what the    │
                       │     agent matches against config.                │
                       └──────────────────┬───────────────────────────────┘
                                          │ registered by FQN in
                                          ▼
                       ┌──────────────────────────────────────────────────┐
                       │  3. The META-INF/services file                   │
                       │     Path is the FQN of the INTERFACE; contents   │
                       │     are the FQN of the IMPL CLASS, one per line. │
                       │     Read by java.util.ServiceLoader at runtime.  │
                       │     Lives in src/main/resources/META-INF/services│
                       └──────────────────┬───────────────────────────────┘
                                          │ packaged into a JAR that lands on
                                          ▼
                       ┌──────────────────────────────────────────────────┐
                       │  4. The application classpath                    │
                       │     The JAR holding (2) + (3) must be reachable  │
                       │     by the application's class loader. NOT       │
                       │     bundled inside the agent jar — alongside it. │
                       └──────────────────┬───────────────────────────────┘
                                          │ selected by name() == config value
                                          ▼
                       ┌──────────────────────────────────────────────────┐
                       │  5. arachna-agent.cfg                            │
                       │     session_resolver=<name>                      │
                       │     jpa_proxy_resolver=<name>                    │
                       │     The agent picks the impl whose name() method │
                       │     returns this exact string.                   │
                       └──────────────────────────────────────────────────┘
```

---

## What each file looks like

Concretely, for a fictional `MdcSessionIdResolver` you might write
yourself:

**1. The interface — already exists in shared, you don't write this:**

```
arachna-trace-shared/spi/session-resolver-api/src/main/java/
  com/github/gabert/arachna/trace/agent/session/SessionIdResolver.java
```

```java
public interface SessionIdResolver {
    String name();
    default void init(Map<String, String> config) {}
    String resolve();
}
```

**2. The impl class — yours:**

```
your-app/src/main/java/com/example/spi/MdcSessionIdResolver.java
```

```java
package com.example.spi;

import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;

public class MdcSessionIdResolver implements SessionIdResolver {
    @Override public String name()    { return "mdc"; }
    @Override public String resolve() { return org.slf4j.MDC.get("requestId"); }
}
```

**3. The ServiceLoader registration — yours:**

```
your-app/src/main/resources/META-INF/services/
  com.github.gabert.arachna.trace.spi.session.SessionIdResolver
```

The **filename** is the interface's fully-qualified name. The
**contents** are the impl class's fully-qualified name, one per line:

```
com.example.spi.MdcSessionIdResolver
```

**4. Maven dependency on the SPI api jar (so the interface compiles):**

```xml
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>SessionResolverApi</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**5. Agent config:**

```properties
session_resolver=mdc
```

The `mdc` here must match what `name()` returns. Off-by-letter and
the agent silently falls back to no-op.

---

## How loading actually works

The agent uses standard `java.util.ServiceLoader` — there is no magic
on top.

1. The agent reads `session_resolver=<name>` from `arachna-agent.cfg`.
2. **Lazy** — no SPI lookup happens until the first instrumented
   method entry. This is critical for Spring Boot, where the agent
   sees the application classloader only after Spring's
   `LaunchedURLClassLoader` is established.
3. On first hit, the agent calls
   `ServiceLoader.load(SessionIdResolver.class, contextClassloader)`.
4. ServiceLoader walks every JAR on the classloader, looks for
   `META-INF/services/com.github.gabert.arachna.trace.spi.session.SessionIdResolver`
   in each, and instantiates **every class listed in any such file** — across
   all jars combined.
5. The agent walks the instantiated providers and picks the one whose
   `name()` returns the configured value.
6. If no match: warning to stderr, falls back to no-op.
7. If match: the resolver's `init(config)` runs, then it's cached for
   subsequent `resolve()` calls.

The `JpaProxyResolver` flow is identical — same `ServiceLoader` shape,
different interface FQN.

---

## Classloader concerns

A Java agent attached via `-javaagent:` runs in a different classloader
than your application code. Anything that crosses that boundary —
ServiceLoader lookups, instanceof checks, type identity — has to be
designed around it. This section covers the classloader rules the agent
follows, the symptoms they prevent, and the failure modes that appear
when they're broken.

### Why the agent uses the *context* classloader, not its own

The agent's classes (`ArachnaTraceAgent`, the recorder, the bundled Jackson
and ByteBuddy) are loaded by the **system classloader** at JVM startup,
because that's where `-javaagent:` puts them. If the agent called
`ServiceLoader.load(SessionIdResolver.class)` with no explicit
classloader argument, the lookup would walk only the system
classloader's classpath — which doesn't contain your app's jars.

The fix is the explicit form:

```java
ServiceLoader.load(SessionIdResolver.class,
                   Thread.currentThread().getContextClassLoader())
```

The context classloader is set by the framework that hosts your code —
Spring Boot's `LaunchedURLClassLoader`, Tomcat's `WebappClassLoader`,
WildFly's module CL, etc. — and is a child (directly or indirectly) of
the system CL. Walking from the context CL upward, ServiceLoader sees
the framework's jars *and* the agent's jars, and it picks up the
`META-INF/services/...` entries from both.

This is why the lookup is **lazy** (deferred until the first
instrumented method entry). At `premain` time, the context CL is still
the bootstrap CL — your app's jars haven't been mounted yet. Looking
up the SPI then would find nothing. By waiting until the first
instrumented call, the agent guarantees the framework has finished
setting up the right CL hierarchy.

### The two-copies-of-the-interface problem (and why we don't shade SPI api jars)

A `Class` object in the JVM is identified not just by its FQN but by
the pair `(FQN, defining classloader)`. If the **same** `.class` file
is loaded by two different classloaders, you end up with **two
distinct `Class` objects** that share a name but are not equal.

A concrete failure mode:

1. Suppose we shaded `SessionResolverApi.jar` into the agent's fat
   jar. Now `SessionIdResolver` is loaded by the **system CL** as
   part of the agent.
2. Your app also depends on `SessionResolverApi` as a normal Maven dep.
   Your impl class `MdcSessionIdResolver` is loaded by the **app CL**
   and references the `SessionIdResolver` it sees from the app CL.
3. ServiceLoader instantiates `MdcSessionIdResolver` in the app CL.
   The instance is of type `SessionIdResolver` *as defined by the app CL*.
4. The agent then does an `instanceof` check against
   `SessionIdResolver` *as defined by the system CL*.
5. The check returns **false**. Same FQN, two different `Class`
   objects, no relation.

Symptom: ServiceLoader sees your impl, the agent silently rejects it
("not a `SessionIdResolver`"), no SPI activates, no error printed.

**This is why the SPI api jars are deliberately NOT shaded into the
agent.** They ship as their own thin jars (`SessionResolverApi`,
`JpaProxyResolverApi`) on the application classpath alongside your
impl. Both your impl class and the agent's lookup code resolve
`SessionIdResolver` to the **same** `Class` object via the app CL,
and the `instanceof` succeeds.

The asymmetric rule:

| Dependency | What we do | Why |
|---|---|---|
| Jackson, ByteBuddy | shaded + relocated into the agent jar | Conflicts with the user's versions are silent and devastating; relocation keeps them invisible to the user. |
| SPI api jars | **NOT shaded**; ship as own thin jars | Identity of the SPI interface must be the same in both classloaders, or `instanceof` fails. |

### Spring Boot's `LaunchedURLClassLoader`

Spring Boot's executable fat jar isn't a normal jar. Its layout puts
your application classes under `BOOT-INF/classes/` and its
dependencies under `BOOT-INF/lib/*.jar` — neither of which is on the
JVM's normal classpath when the JVM starts. Spring Boot's launcher
runs first, scans those locations, and constructs a custom
`LaunchedURLClassLoader` that knows how to load from inside-the-jar
URLs.

For the agent, two consequences:

1. **At `premain` time, your impl jar is invisible.** It's inside
   `BOOT-INF/lib/`. The system CL hasn't been told it exists yet.
   This is why the SPI lookup MUST be lazy.
2. **By the first instrumented call, `LaunchedURLClassLoader` is
   the context CL** (Spring Boot sets it up before your `main`
   runs application code). At that point ServiceLoader can find
   your impl jar by walking from the context CL.

If you see "WARNING: session_resolver=X not found on classpath" *only*
in a Spring Boot fat jar, but the same impl works when run as plain
classes-on-classpath, the lookup is firing **before** Spring Boot's CL
is in place. That shouldn't happen with the lazy design; if it does,
it's an agent bug worth filing.

### OSGi / JBoss Modules / WildFly — untested territory

> **Note.** Arachna Trace has not been tested in OSGi, JBoss Modules,
> or WildFly. Nothing in this section is a validated recipe — it's a
> sketch of what *might* go wrong and the kinds of plumbing you'd
> *probably* need, intended to give a first-time integrator a
> starting point. If you successfully wire the agent into one of
> these containers, contributions / war stories are welcome.

These containers replace the standard parent-delegation classloader
hierarchy with **module-isolated** classloaders, where each module
sees only its declared dependencies. That likely breaks
ServiceLoader's normal "walk the classpath" assumption — and the
context-classloader strategy the agent uses everywhere else.

Things to think about:

- **OSGi:** ServiceLoader inside a bundle walks the bundle
  classloader, which only sees the bundle's own classpath plus its
  `Import-Package` / `Require-Bundle` entries. If the SPI api package
  isn't imported by the bundle that hosts your impl, the impl class
  probably isn't visible to the agent's lookup. A possible starting
  point: declare the SPI api package as an `Import-Package` in your
  bundle's manifest, and make sure the api-providing bundle is
  active before yours. There may also be additional
  ServiceLoader-mediator concerns.
- **JBoss Modules / WildFly:** modules are isolated by default. The
  agent jar likely lives outside the module system (system
  classloader, via `-javaagent:`), but SPI impls deployed as app
  modules would still need explicit module dependencies on the SPI
  api — typically declared via `jboss-deployment-structure.xml` or
  the equivalent. Whether a `-javaagent:` agent's context
  classloader can even reach app-module classes is something we
  haven't verified.

If you're attempting either of these, expect classpath plumbing — and
when the agent reports "not found on classpath," remember that "the
classpath" inside a module-isolated container is module-scoped, not
global. The diagnostic snippet in the next subsection will tell you
what your context classloader actually sees.

> **Fallback that always works.** If wiring a framework- or
> container-specific session resolver turns out to be more trouble
> than it's worth, the built-in `config` resolver ships in the
> agent and needs no classloader plumbing whatsoever — it just
> reads a string from `arachna-agent.cfg` and returns it. You
> trade automatic per-request session detection for an explicit
> grouping label you set yourself (per JVM run, per tenant, per
> deploy, per batch job — whatever logical unit makes sense for
> your traces). Documented at
> [session-resolver.md → Built-in: config — the universal fallback](session-resolver.md#built-in-config--the-universal-fallback).
> Same fallback applies for any unsupported framework or container,
> not just OSGi / WildFly.

### Diagnosing classloader issues

When the agent reports "not found" but you're sure your jar is
there, the cause is usually a CL mismatch. To confirm, drop this
into your application's startup code (e.g. a `@PostConstruct` on a
`@Configuration` bean) so it runs after the framework has wired up
the CL hierarchy:

```java
import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;

ClassLoader ctx = Thread.currentThread().getContextClassLoader();
System.err.println("=== context CL chain ===");
for (ClassLoader cl = ctx; cl != null; cl = cl.getParent()) {
    System.err.println("  " + cl.getClass().getName() + " @ " +
                       Integer.toHexString(System.identityHashCode(cl)));
}

System.err.println("=== SPI interface CL ===");
System.err.println("  " + SessionIdResolver.class.getClassLoader());

System.err.println("=== ServiceLoader visibility ===");
java.util.ServiceLoader.load(SessionIdResolver.class, ctx)
    .forEach(p -> System.err.println("  found: " + p.getClass().getName() +
                                     " (" + p.name() + ") loaded by " +
                                     p.getClass().getClassLoader()));
```

What to look for:

- Does ServiceLoader find your impl at all? If not, your jar isn't on
  the context CL's reachable path.
- Does the SPI interface's classloader match your impl's classloader?
  If not, you've got the two-copies-of-the-interface problem (above).
  Check whether you accidentally shaded the SPI api jar somewhere.
- Is the context CL the framework's expected CL? On Spring Boot you
  expect to see `LaunchedURLClassLoader` in the chain; if you see only
  `AppClassLoader`, something has reset the context CL.

### Summary

| Concern | What the agent does to handle it |
|---|---|
| Agent in system CL, app in app CL | Lazy ServiceLoader using the **context** CL, not the system CL |
| `LaunchedURLClassLoader` not ready at `premain` | Defer SPI lookup until the first instrumented method entry |
| Two copies of the SPI interface → `instanceof` fails | Ship SPI api jars as **non-shaded** thin jars on the app classpath |
| Jackson / ByteBuddy version conflicts with user's app | Shade + relocate into the agent jar |
| Module-isolated containers (OSGi / WildFly) | Untested. Tips only — see "untested territory" subsection above |

---

## Reference impls — the educative source

The shipped reference impls under
[`arachna-trace-jvm-extensions/`](../../../../arachna-trace-jvm-extensions/)
each demonstrate the full recipe in three files. Open any of them
and you see exactly what your own impl module needs to look like:

| Impl | Source layout |
|---|---|
| `session-resolver-config` | `pom.xml` (one dep) + `ConfigSessionIdResolver.java` + `META-INF/services/...SessionIdResolver` (one line) |
| `session-resolver-spring` | same shape; adds `SessionIdFilter` + `SessionIdHolder` for the per-request thread-local |
| `jpa-proxy-resolver-hibernate` | same shape; `HibernateJpaProxyResolver.java` uses reflection so it has no compile-time Hibernate dep |

Copying any of these as a template is the fastest path. The
[`README`](../../../../arachna-trace-jvm-extensions/README.md) of
`jvm-extensions/` walks through the same recipe.

---

## How to verify a deployed setup

When something isn't working in a deployed JVM (a container, a
production-like environment), here are the inspection commands. They
work against any JVM with the agent attached.

### 1. Did the agent attach? Is your impl loaded?

```bash
# In container logs (or wherever the agent's stderr goes), look for:
[ArachnaTrace] Agent attached — destination=<...>, matchers=N include / M exclude
[ArachnaTrace] SessionIdResolver: looking for '<name>'
[ArachnaTrace] SessionIdResolver: found '<name>' (com.example.spi.MdcSessionIdResolver)
[ArachnaTrace] SessionIdResolver: activated '<name>'
```

Same pattern for `JpaProxyResolver`. If you see "looking for" but no
"found", the name in your config doesn't match any impl's `name()`.

### 2. Is your META-INF/services file inside the deployed JAR?

```bash
# In the container, list ServiceLoader registrations seen by your app
unzip -p /app/your-app.jar | grep -l "META-INF/services"

# Or for a Spring Boot fat jar: unpack it and grep recursively
docker exec <container> sh -c '
  cd /tmp && unzip -o /app/app.jar -d unpacked > /dev/null &&
  find unpacked -path "*META-INF/services*"
'
```

### 3. Does the META-INF/services file contain the correct class FQN?

```bash
docker exec <container> sh -c '
  cd /tmp/unpacked &&
  cat META-INF/services/com.github.gabert.arachna.trace.spi.session.SessionIdResolver
  # Expect: a single line with your impl FQN, e.g. com.example.spi.MdcSessionIdResolver
'
```

### 4. Does that class actually exist in the JAR?

```bash
docker exec <container> sh -c '
  cd /tmp/unpacked &&
  find . -name "MdcSessionIdResolver.class"
'
```

If (3) names a class that (4) doesn't find, you get
`ServiceConfigurationError` (see "Common failures" below).

---

## Common failures and what they look like

### A — `ServiceConfigurationError: Provider X not found`

```
java.util.ServiceConfigurationError:
  com.github.gabert.arachna.trace.spi.session.SessionIdResolver:
  Provider com.example.spi.MdcSessionIdResolver not found
```

**Cause:** A `META-INF/services/...SessionIdResolver` file exists on
the classpath that names a class that isn't there. ServiceLoader can't
instantiate it, throws.

**Common origin:** code refactor moved a class to a new package /
deleted it / renamed it, but the matching `META-INF/services`
registration was left pointing at the old FQN. The compiler doesn't
catch this — the file is plain text, the build system doesn't validate
it.

**Fix:**

1. Find every `META-INF/services/<interface FQN>` on the classpath.
2. Update or delete entries that point at non-existent classes.
3. Rebuild and redeploy.

> **Tip — promoting source between modules.** When a class moves from
> one module to another, the `.java` files are easy to track but the
> matching `src/main/resources/META-INF/services/` registration is
> easy to forget. If the old location still ships a registration file
> pointing at the moved class's old FQN, you get exactly this error
> at runtime — and the unit tests don't catch it because they don't
> run a real ServiceLoader scan against a packaged fat jar. Audit
> `META-INF/services/` in both source and destination locations
> whenever you `git mv` a class that backs an SPI.

### B — `[ArachnaTrace] WARNING: session_resolver='X' not found on classpath`

**Cause:** The agent looked at every loaded provider and none of them
returned the configured name from `name()`.

**Possible reasons:**
- Config value typo (`spring-sesssion` vs `spring-session`).
- The impl JAR is missing from the application classpath (it's a
  resolver impl jar — it must be a Maven dep of *your* app, not
  bundled in the agent jar).
- The impl exists but its `name()` returns a different string than
  you typed in config.

**Fix:** check `arachna-agent.cfg` against the impl's `name()` method.
Verify the impl JAR is a transitive dep of your app
(`mvn dependency:tree | grep -i resolver`).

### C — Filter / setup class is referenced but not registered

For SPIs that need a per-request thread-local population
(`session-resolver-spring`'s `SessionIdFilter`), the filter is a
plain Jakarta Servlet `Filter` with **no** `@Component` annotation.
You must register it explicitly in your app — typically as a `@Bean`
in a Spring `@Configuration` (e.g. on your main `@SpringBootApplication`
class). If you forget this:

- `SpringSessionIdResolver.resolve()` returns `null` for every call.
- No `SI;` lines appear in traces.
- No exception is thrown, so the symptom is "session tracking just
  isn't working."

**Fix:** add a `@Bean` for the filter:

```java
@Bean
public SessionIdFilter sessionIdFilter() {
    return new SessionIdFilter();
}
```

This is intentional design (no `@Component` magic — registration is
explicit and visible in your app), see
[`arachna-trace-jvm-extensions/session-resolver-spring/.../SessionIdFilter.java`](../../../../arachna-trace-jvm-extensions/session-resolver-spring/src/main/java/com/github/gabert/arachna/trace/agent/session/spring/SessionIdFilter.java).

### D — Two impls registered, the wrong one wins

`ServiceLoader` instantiates **all** registered providers across all
jars. The agent then picks the one whose `name()` matches your config
value. Two impls returning the same `name()` is a configuration bug —
behavior is undefined (whichever ServiceLoader yields first).

**Fix:** ensure only one impl per `name()` value on the classpath.

### E — JAR on the wrong classpath

The agent JAR is loaded by the system classloader (via `-javaagent:`),
but the application — and therefore SPI impl JARs — is loaded by the
application classloader. The agent's lazy ServiceLoader uses the
**context classloader** so it can see your app's jars at the time of
the first instrumented call.

If you accidentally bundle the impl into the agent's shaded fat jar,
or place it in the JDK's `lib/ext`, the context classloader may not
see it. The conventional layout — impl JAR as a normal Maven
dependency of your app, packaged into your app's fat jar — Just Works.

---

## Adding a new SPI interface (for project contributors)

If you want to add a brand-new SPI (e.g. a per-tenant routing hook, a
redaction hook), the pattern is:

1. Add a new `<spi-name>-api/` module under `arachna-trace-shared/spi/`
   with a single thin interface.
2. Add a load point in
   `arachna-trace-agents/jvm/core/agent/.../agent/spi/SpiBootstrap.java`
   that does the lazy `ServiceLoader` + name-match dance.
3. Read the configured name from `arachna-agent.cfg` and call the
   resolver from wherever in the agent it's relevant.
4. Document it in `arachna-trace-agents/jvm/docs/reference/<spi-name>-resolver.md`.
5. Ship a reference impl in `arachna-trace-jvm-extensions/` so a
   user has at least one worked example to copy.

The educative principle: every SPI ships with at least one reference
impl, and every reference impl shows the entire 4-piece recipe with
no surprise dependencies.

---

## See also

- [Session ID Resolver SPI](session-resolver.md) — the specific contract for `SessionIdResolver`
- [JPA Proxy Resolver SPI](jpa-proxy-resolver.md) — the specific contract for `JpaProxyResolver`
- [`arachna-trace-jvm-extensions/README.md`](../../../../arachna-trace-jvm-extensions/README.md) — overview of the shipped reference impls
- [Architecture: extension points](../architecture.md#extension-points-spis) — how SPIs fit in the agent's design
