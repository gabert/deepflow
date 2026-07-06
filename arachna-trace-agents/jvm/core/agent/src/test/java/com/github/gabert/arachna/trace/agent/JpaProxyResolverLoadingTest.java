package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;
import com.github.gabert.arachna.trace.agent.spi.SpiLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class JpaProxyResolverLoadingTest {

    // --- Selection by name across multiple registered providers ---

    @Test
    void middleProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("jpa_proxy_resolver=beta");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("beta", resolver.name());
    }

    @Test
    void firstProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("jpa_proxy_resolver=alpha");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("alpha", resolver.name());
    }

    @Test
    void lastProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.from("jpa_proxy_resolver=gamma");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("gamma", resolver.name());
    }

    // --- Unmatched name returns null ---

    @Test
    void unmatchedNameReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.from("jpa_proxy_resolver=nonexistent");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, testClassLoader);

        assertNull(resolver);
    }

    // --- No jpa_proxy_resolver configured — returns null without SPI lookup ---

    @Test
    void noConfigReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.from("");

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config,
                Thread.currentThread().getContextClassLoader());

        assertNull(resolver);
    }

    // --- Empty classloader, name configured — returns null ---

    @Test
    void noSpiOnClasspathReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.from("jpa_proxy_resolver=beta");
        ClassLoader emptyClassLoader = new URLClassLoader(new java.net.URL[0], null);

        JpaProxyResolver resolver = SpiLoader.loadJpaProxyResolver(config, emptyClassLoader);

        assertNull(resolver);
    }
}
