package com.github.gabert.deepflow.agent.session;

/**
 * SPI for resolving the current logical session ID from framework-specific
 * thread-local state (e.g. Servlet session, Spring request context).
 *
 * Implementations must be stateless and thread-safe. The resolver is called
 * on every instrumented method entry, so it should be fast — typically a
 * single ThreadLocal read.
 *
 * Register implementations via Java ServiceLoader:
 * META-INF/services/com.github.gabert.deepflow.agent.session.SessionIdResolver
 *
 * The agent selects which resolver to use via the {@code session_resolver}
 * config property, matching against {@link #name()}.
 */
public interface SessionIdResolver {

    /**
     * Unique name used to select this resolver via the {@code session_resolver}
     * config property (e.g. "config", "servlet", "noop").
     */
    String name();

    /**
     * Return the current session/request ID for the calling thread,
     * or {@code null} if no session context is available.
     */
    String resolve();
}
