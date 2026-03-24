package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

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
