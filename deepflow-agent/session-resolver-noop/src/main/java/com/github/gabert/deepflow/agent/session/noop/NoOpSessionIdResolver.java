package com.github.gabert.deepflow.agent.session.noop;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

/**
 * Resolver that returns {@code null} — no session tracking.
 *
 * Activate via: {@code session_resolver=noop}
 *
 * This module also serves as a reference example for implementing
 * a custom SessionIdResolver. See SESSION_RESOLVER_SPI.md.
 */
public final class NoOpSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public String resolve() {
        return null;
    }
}
