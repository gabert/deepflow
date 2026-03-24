# JPA Proxy Resolver SPI

The `JpaProxyResolver` SPI allows the agent to unwrap JPA framework proxy
objects during serialization so that their full state is captured in trace
output instead of an opaque `<proxy>` marker.

## The problem

JPA providers like Hibernate substitute proxy objects for lazy-loaded entities
and wrap collections (e.g. `PersistentBag`, `PersistentSet`). The agent's
default proxy detection emits `<proxy>` for these because the runtime class
does not match the declared type. This loses potentially valuable data.

## Interface

```java
package com.github.gabert.deepflow.jpaproxy;

public interface JpaProxyResolver {
    String name();                 // unique name used for selection via config
    Object resolve(Object proxy);  // unwrap proxy, or return null
}
```

| Method      | Contract                                                     |
|-------------|--------------------------------------------------------------|
| `name()`    | A short, unique identifier (e.g. `"hibernate"`). The agent matches this against the `jpa_proxy_resolver` config property. |
| `resolve()` | Attempt to unwrap a proxy or collection wrapper to the real underlying object. Return `null` if the object is not a recognized proxy or cannot be unwrapped (e.g. Hibernate session closed, collection not initialized). |

Implementations must be **thread-safe** — the resolver is called during CBOR
serialization which can happen concurrently from multiple application threads.

## Configuration

Enable a resolver by setting `jpa_proxy_resolver` in `deepagent.cfg`:

```properties
jpa_proxy_resolver=hibernate
```

The value must match the `name()` of exactly one resolver on the classpath.

## How the agent loads the resolver

Loading is **lazy** — no SPI lookup happens until the first instrumented method
is entered. This ensures the application's classloaders (and Hibernate itself)
are fully initialized before the resolver is instantiated.

1. Read `jpa_proxy_resolver` from agent config.
2. If not set → no resolver is loaded. Proxies are handled by the agent's
   default behavior (`<proxy>` marker).
3. If set → use `ServiceLoader` with the current thread's context classloader
   (falling back to the system classloader) to find all
   `JpaProxyResolver` providers.
4. Select the provider whose `name()` matches the config value.
5. If no match → print a warning to `stderr`. No resolver is used.

Once loaded, the resolver is stored in `Codec` as a static reference and
called from `EnvelopeSerializer` during every object serialization.

## How the resolver is called during serialization

```
EnvelopeSerializer.serialize(value)
  ├─ Cycle detection (IdentityHashMap)
  ├─ JpaProxyResolver.resolve(value)
  │   ├─ returns non-null → use unwrapped object for serialization
  │   └─ returns null     → fall through to proxy detection
  ├─ isProxy(value) check
  │   ├─ true  → emit <proxy> marker
  │   └─ false → serialize normally with envelope
  └─ Emit envelope: { object_id, class, value }
```

The resolver is called **before** the proxy fallback check. If it successfully
unwraps the object, serialization continues with the real object and the full
state is captured. If it returns `null`, the default proxy detection takes over.

## Built-in implementation: hibernate

| Property | Value |
|----------|-------|
| Name     | `hibernate` |
| Module   | `jpa-proxy-resolver-hibernate` |

Handles two categories of Hibernate proxies:

### Entity proxies

Classes whose name contains `$HibernateProxy$` (e.g.
`AuthorEntity$HibernateProxy$abc123`). Unwrapped via reflection:

```
proxy.getHibernateLazyInitializer().getImplementation()
```

Returns the real entity object with all fields populated.

### Collection wrappers

Classes in the `org.hibernate.collection.*` package (`PersistentBag`,
`PersistentSet`, `PersistentMap`). Copied to plain Java collections:

| Hibernate wrapper | Copied to |
|-------------------|-----------|
| `PersistentBag` (implements `List`) | `ArrayList` |
| `PersistentSet` (implements `Set`) | `LinkedHashSet` |
| `PersistentMap` (implements `Map`) | `LinkedHashMap` |

### No compile-time Hibernate dependency

The resolver uses reflection exclusively — there is no compile-time dependency
on Hibernate. At runtime, Hibernate classes are expected to be on the
application's classpath. If they are not (or if the session is closed and the
proxy is uninitialized), `resolve()` returns `null` and the agent falls back to
the `<proxy>` marker.

## Default behavior summary

| Scenario | Behavior |
|----------|----------|
| `jpa_proxy_resolver` not configured | No resolver loaded; proxies emit `<proxy>` |
| `jpa_proxy_resolver=hibernate` with initialized proxy | Full object state captured |
| `jpa_proxy_resolver=hibernate` with closed session | `resolve()` returns `null`; `<proxy>` emitted |
| Named resolver not found on classpath | Warning to stderr; proxies emit `<proxy>` |

## Writing a custom resolver

1. Create a class implementing `JpaProxyResolver`.
2. Register it via the standard Java ServiceLoader mechanism — create the file:
   ```
   META-INF/services/com.github.gabert.deepflow.jpaproxy.JpaProxyResolver
   ```
   containing the fully qualified class name of your implementation.
3. Place the JAR on the application classpath (not inside the agent JAR).
4. Set `jpa_proxy_resolver=<your-name>` in `deepagent.cfg`.

**Example — resolver for EclipseLink:**

```java
public class EclipseLinkProxyResolver implements JpaProxyResolver {

    @Override
    public String name() {
        return "eclipselink";
    }

    @Override
    public Object resolve(Object proxy) {
        // Use EclipseLink-specific API to unwrap
        // Return null if not an EclipseLink proxy
        return null;
    }
}
```
