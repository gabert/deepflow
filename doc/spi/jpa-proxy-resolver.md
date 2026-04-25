# JPA Proxy Resolver SPI

The `JpaProxyResolver` SPI unwraps JPA framework proxy objects during
serialization so their full state is captured instead of an opaque `<proxy>`
marker.

## The problem

JPA providers like Hibernate substitute proxy objects for lazy-loaded entities
and wrap collections (e.g. `PersistentBag`, `PersistentSet`). The agent's
default proxy detection emits `<proxy>` for these because the runtime class
does not match the declared type.

## Interface

```java
package com.github.gabert.deepflow.jpaproxy;

public interface JpaProxyResolver {
    String name();                 // unique name for selection via config
    Object resolve(Object proxy);  // unwrap proxy, or return null
}
```

| Method | Contract |
|--------|----------|
| `name()` | Short unique identifier (e.g. `"hibernate"`). Matched against `jpa_proxy_resolver` config. |
| `resolve()` | Unwrap proxy to real object. Return `null` if not a recognized proxy or cannot be unwrapped. |

Implementations must be thread-safe.

## Configuration

```properties
jpa_proxy_resolver=hibernate
```

If not set, no resolver is loaded and proxies emit `<proxy>`.

## Loading behavior

Lazy -- loaded on first instrumented method entry. Uses `ServiceLoader`
with context classloader, same pattern as `SessionIdResolver`.

Once loaded, the resolver is stored in `Codec` as a static reference and
called from `EnvelopeSerializer` during every object serialization.

## How it's called during serialization

```
EnvelopeSerializer.serialize(value)
  +-- Cycle detection (IdentityHashMap)
  +-- JpaProxyResolver.resolve(value)
  |   +-- returns non-null -> use unwrapped object
  |   +-- returns null     -> fall through to proxy detection
  +-- isProxy(value) check
  |   +-- true  -> emit <proxy> marker
  |   +-- false -> serialize normally with envelope
  +-- Emit envelope: { object_id, class, value }
```

The resolver is called **before** the proxy fallback. If it unwraps
successfully, the full object state is captured.

## Built-in: hibernate

| Property | Value |
|----------|-------|
| Name | `hibernate` |
| Module | `jpa-proxy-resolver-hibernate` |

Handles two categories:

**Entity proxies** -- classes containing `$HibernateProxy$`. Unwrapped via:
```
proxy.getHibernateLazyInitializer().getImplementation()
```

**Collection wrappers** -- classes in `org.hibernate.collection.*`:

| Hibernate wrapper | Copied to |
|-------------------|-----------|
| `PersistentBag` (List) | `ArrayList` |
| `PersistentSet` (Set) | `LinkedHashSet` |
| `PersistentMap` (Map) | `LinkedHashMap` |

Uses reflection exclusively -- no compile-time Hibernate dependency. If
Hibernate is not on the classpath or the session is closed, `resolve()`
returns `null`.

## Writing a custom resolver

1. Implement `JpaProxyResolver`
2. Register via ServiceLoader:
   ```
   META-INF/services/com.github.gabert.deepflow.jpaproxy.JpaProxyResolver
   ```
3. Place JAR on application classpath
4. Set `jpa_proxy_resolver=<your-name>` in config

**Example -- EclipseLink:**

```java
public class EclipseLinkProxyResolver implements JpaProxyResolver {
    @Override public String name() { return "eclipselink"; }
    @Override public Object resolve(Object proxy) {
        // Use EclipseLink API to unwrap
        return null;
    }
}
```

## Behavior summary

| Scenario | Behavior |
|----------|----------|
| Not configured | Proxies emit `<proxy>` |
| `hibernate` with initialized proxy | Full object state captured |
| `hibernate` with closed session | `resolve()` returns `null`, `<proxy>` emitted |
| Named resolver not found | Warning to stderr, `<proxy>` emitted |
