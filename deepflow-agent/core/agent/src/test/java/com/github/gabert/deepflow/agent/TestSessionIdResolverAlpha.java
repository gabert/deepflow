package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

public class TestSessionIdResolverAlpha implements SessionIdResolver {

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public String resolve() {
        return "alpha-session";
    }
}
