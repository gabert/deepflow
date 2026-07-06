package com.github.gabert.arachna.trace.agent.spi;

import com.github.gabert.arachna.trace.agent.AgentConfig;
import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;
import com.github.gabert.arachna.trace.codec.envelope.JpaProxyResolvers;
import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

/**
 * Lazy, double-checked-locking bootstrap for the agent's SPIs.
 *
 * <p>Both SPIs are loaded on first use rather than at agent startup, because
 * SPI implementations live on the application classpath which may not be
 * fully initialized when {@code premain} runs. The first traced method call
 * triggers loading.</p>
 *
 * <p>Thread-safety: both lazy-init paths use double-checked locking. Once
 * initialized, reads are lock-free.</p>
 */
public final class SpiBootstrap {
    private final AgentConfig config;
    private volatile SessionIdResolver sessionIdResolver;
    private volatile boolean jpaProxyResolverInitialized;

    public SpiBootstrap(AgentConfig config) {
        this.config = config;
    }

    /**
     * Returns the configured {@link SessionIdResolver}, loading it on first
     * call. Always returns non-null — falls back to the noop resolver if the
     * configured name is unset or unresolvable.
     */
    public SessionIdResolver getSessionIdResolver() {
        SessionIdResolver r = sessionIdResolver;
        if (r != null) return r;
        synchronized (this) {
            r = sessionIdResolver;
            if (r != null) return r;
            r = SpiLoader.loadSessionIdResolver(config, SpiLoader.resolveClassLoader());
            r.init(config.getConfigMap());
            sessionIdResolver = r;
            return r;
        }
    }

    /**
     * Loads the configured {@link JpaProxyResolver} (if any) and registers it
     * with {@link JpaProxyResolvers}. Idempotent — subsequent calls are no-ops.
     */
    public void initJpaProxyResolverOnce() {
        if (jpaProxyResolverInitialized) return;
        synchronized (this) {
            if (jpaProxyResolverInitialized) return;
            JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, SpiLoader.resolveClassLoader());
            if (resolver != null) {
                JpaProxyResolvers.setActive(resolver);
            }
            jpaProxyResolverInitialized = true;
        }
    }
}
