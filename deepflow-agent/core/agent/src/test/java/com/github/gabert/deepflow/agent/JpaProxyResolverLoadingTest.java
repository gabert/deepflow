package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class JpaProxyResolverLoadingTest {

    // --- SPI selection by name ---

    @Test
    void spiProviderSelectedByName() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=beta");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("beta", resolver.name());
    }

    // --- Middle provider selected when multiple are on classpath ---

    @Test
    void middleProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=beta");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("beta", resolver.name());
    }

    @Test
    void firstProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=alpha");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("alpha", resolver.name());
    }

    @Test
    void lastProviderSelectedFromMultiple() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=gamma");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, testClassLoader);

        assertNotNull(resolver);
        assertEquals("gamma", resolver.name());
    }

    // --- Unmatched name returns null ---

    @Test
    void unmatchedNameReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=nonexistent");
        ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, testClassLoader);

        assertNull(resolver);
    }

    // --- No jpa_proxy_resolver configured — returns null without SPI lookup ---

    @Test
    void noConfigReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.getInstance("");

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config,
                Thread.currentThread().getContextClassLoader());

        assertNull(resolver);
    }

    // --- Empty classloader, name configured — returns null ---

    @Test
    void noSpiOnClasspathReturnsNull() throws IOException {
        AgentConfig config = AgentConfig.getInstance("jpa_proxy_resolver=beta");
        ClassLoader emptyClassLoader = new URLClassLoader(new java.net.URL[0], null);

        JpaProxyResolver resolver = DeepFlowAdvice.loadJpaProxyResolver(config, emptyClassLoader);

        assertNull(resolver);
    }
}
