package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;

public class TestSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String resolve() {
        return "test-session-123";
    }
}
