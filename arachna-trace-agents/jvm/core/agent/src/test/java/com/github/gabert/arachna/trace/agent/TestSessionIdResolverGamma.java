package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;

public class TestSessionIdResolverGamma implements SessionIdResolver {

    @Override
    public String name() {
        return "gamma";
    }

    @Override
    public String resolve() {
        return "gamma-session";
    }
}
