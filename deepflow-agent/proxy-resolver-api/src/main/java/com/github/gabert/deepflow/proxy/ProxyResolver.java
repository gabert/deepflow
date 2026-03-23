package com.github.gabert.deepflow.proxy;

/**
 * SPI for resolving framework proxy objects to their underlying real objects.
 *
 * When the agent encounters a proxy (Hibernate, CGLIB, JDK dynamic), it
 * normally emits {@code <proxy>} instead of serializing the object.
 * A ProxyResolver implementation can unwrap the proxy and return the
 * real object so the agent captures its full state.
 *
 * Implementations must be thread-safe. The resolver is called during
 * serialization of every proxy object encountered in traced method
 * arguments, return values, and this-instances.
 *
 * Register implementations via Java ServiceLoader:
 * META-INF/services/com.github.gabert.deepflow.proxy.ProxyResolver
 *
 * The agent selects which resolver to use via the {@code proxy_resolver}
 * config property, matching against {@link #name()}.
 */
public interface ProxyResolver {

    /**
     * Unique name used to select this resolver via the {@code proxy_resolver}
     * config property (e.g. "hibernate").
     */
    String name();

    /**
     * Attempt to resolve a proxy object to its underlying real object.
     *
     * @param proxy the proxy object detected by the agent
     * @return the real unwrapped object, or {@code null} if the proxy
     *         cannot be resolved (e.g. session closed, not a recognized proxy)
     */
    Object resolve(Object proxy);
}
