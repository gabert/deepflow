package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.session.SessionIdResolver;
import com.github.gabert.arachna.trace.agent.spi.SpiLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdResolverLoadingTest {

    // --- Selection by name across multiple registered providers ---

    @Test
    void middleProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("session_resolver=test");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config, testClassLoader);

        assertEquals("test", resolver.name());
        assertEquals("test-session-123", resolver.resolve());
    }

    @Test
    void firstProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("session_resolver=alpha");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config, testClassLoader);

        assertEquals("alpha", resolver.name());
        assertEquals("alpha-session", resolver.resolve());
    }

    @Test
    void lastProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("session_resolver=gamma");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config, testClassLoader);

        assertEquals("gamma", resolver.name());
        assertEquals("gamma-session", resolver.resolve());
    }

    // --- Unmatched name falls back to noop ---

    @Test
    void unmatchedNameFallsBackToNoop() throws IOException {
        AgentConfig config = AgentConfig.from("session_resolver=nonexistent");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config, testClassLoader);

        assertNull(resolver.resolve());
    }

    // --- No session_resolver configured — noop without SPI lookup ---

    @Test
    void noConfigMeansNoopWithoutSpiLookup() throws IOException {
        AgentConfig config = AgentConfig.from("");

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config,
                Thread.currentThread().getContextClassLoader());

        assertNull(resolver.resolve());
    }

    // --- Empty classloader, name configured — noop fallback ---

    @Test
    void noSpiOnClasspathFallsBackToNoop() throws IOException {
        AgentConfig config = AgentConfig.from("session_resolver=test");
        ClassLoader emptyClassLoader = new URLClassLoader(new java.net.URL[0], null);

        SessionIdResolver resolver = SpiLoader.loadSessionIdResolver(config, emptyClassLoader);

        assertNull(resolver.resolve());
    }
}
