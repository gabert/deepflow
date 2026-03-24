package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

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
