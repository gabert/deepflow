package com.github.gabert.deepflow.demo;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

/**
 * Demo resolver that returns a fixed session ID to verify the SPI works.
 *
 * Activate via: {@code session_resolver=demo}
 */
public class DemoSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public String resolve() {
        return "demo-session-42";
    }
}
