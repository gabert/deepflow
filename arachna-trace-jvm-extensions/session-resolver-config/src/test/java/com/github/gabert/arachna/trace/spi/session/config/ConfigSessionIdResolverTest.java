package com.github.gabert.arachna.trace.spi.session.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reference test for a SessionIdResolver impl. Mirrors the production class
 * 1:1 — name, init, resolve, and the "missing config key" edge case. Use
 * this shape as a starting point when writing your own resolver's tests.
 */
class ConfigSessionIdResolverTest {

    @Test
    void nameIsConfig() {
        // The agent matches `session_resolver=<value>` in arachna-agent.cfg
        // against this name() to pick the resolver. Pin the contract.
        assertEquals("config", new ConfigSessionIdResolver().name());
    }

    @Test
    void resolveReturnsConfiguredSessionId() {
        ConfigSessionIdResolver r = new ConfigSessionIdResolver();
        r.init(Map.of("session_id", "debug-run-42"));

        assertEquals("debug-run-42", r.resolve());
    }

    @Test
    void resolveReturnsNullWhenSessionIdNotConfigured() {
        // The resolver's contract says missing config means "no session ID"
        // — the agent then disables session tracking. Don't synthesize a
        // value, don't throw.
        ConfigSessionIdResolver r = new ConfigSessionIdResolver();
        r.init(new HashMap<>());

        assertNull(r.resolve());
    }

    @Test
    void resolveReflectsLatestInitCall() {
        // init() may be called more than once across an agent's lifetime
        // (e.g. config reload). The resolver must always return what the
        // most recent init() saw.
        ConfigSessionIdResolver r = new ConfigSessionIdResolver();
        r.init(Map.of("session_id", "first"));
        r.init(Map.of("session_id", "second"));

        assertEquals("second", r.resolve());
    }
}
