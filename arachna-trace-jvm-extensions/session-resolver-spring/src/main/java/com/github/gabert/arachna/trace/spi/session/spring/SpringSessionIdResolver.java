package com.github.gabert.arachna.trace.spi.session.spring;

import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;

/**
 * Reads the HTTP session ID from a ThreadLocal set by {@link SessionIdFilter}.
 *
 * Activate via: {@code session_resolver=spring-session}
 */
public final class SpringSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "spring-session";
    }

    @Override
    public String resolve() {
        return SessionIdHolder.get();
    }
}
