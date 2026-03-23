package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdResolverLoadingTest {

    // --- SPI selection by name ---

    @Test
    void spiProviderSelectedByName() throws IOException {
        AgentConfig config = AgentConfig.getInstance("session_resolver=test");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = DeepFlowAdvice.loadSessionIdResolver(config, testClassLoader);

        assertEquals("test", resolver.name());
        assertEquals("test-session-123", resolver.resolve());
    }

    // --- Unmatched name falls back to noop ---

    @Test
    void unmatchedNameFallsBackToNoop() throws IOException {
        AgentConfig config = AgentConfig.getInstance("session_resolver=nonexistent");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        SessionIdResolver resolver = DeepFlowAdvice.loadSessionIdResolver(config, testClassLoader);

        assertNull(resolver.resolve());
    }

    // --- No session_resolver configured — noop without SPI lookup ---

    @Test
    void noConfigMeansNoopWithoutSpiLookup() throws IOException {
        AgentConfig config = AgentConfig.getInstance("");

        SessionIdResolver resolver = DeepFlowAdvice.loadSessionIdResolver(config,
                Thread.currentThread().getContextClassLoader());

        assertNull(resolver.resolve());
    }

    // --- Empty classloader, name configured — noop fallback ---

    @Test
    void noSpiOnClasspathFallsBackToNoop() throws IOException {
        AgentConfig config = AgentConfig.getInstance("session_resolver=test");
        ClassLoader emptyClassLoader = new URLClassLoader(new java.net.URL[0], null);

        SessionIdResolver resolver = DeepFlowAdvice.loadSessionIdResolver(config, emptyClassLoader);

        assertNull(resolver.resolve());
    }
}
