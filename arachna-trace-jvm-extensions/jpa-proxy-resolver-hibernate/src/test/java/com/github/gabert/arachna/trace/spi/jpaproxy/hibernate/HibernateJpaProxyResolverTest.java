package com.github.gabert.arachna.trace.spi.jpaproxy.hibernate;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pin the dispatch logic in {@link HibernateJpaProxyResolver}. The positive
 * path (actually unwrapping a real Hibernate proxy) lives in the integration
 * tests because it needs a live Hibernate session — too much setup for a
 * unit test.
 *
 * The interesting unit-test surface is what the resolver does NOT match:
 * everything that is not a recognized Hibernate type must return null so the
 * agent falls back to its default handling.
 */
class HibernateJpaProxyResolverTest {

    private final HibernateJpaProxyResolver resolver = new HibernateJpaProxyResolver();

    @Test
    void nameIsHibernate() {
        // The agent matches `jpa_proxy_resolver=<value>` against this. Pin it.
        assertEquals("hibernate", resolver.name());
    }

    @Test
    void plainObjectIsNotResolved() {
        // A regular POJO isn't a Hibernate proxy — return null so the agent
        // emits the object as-is.
        assertNull(resolver.resolve(new Object()));
    }

    @Test
    void plainCollectionIsNotResolved() {
        // ArrayList / HashSet / HashMap class names do NOT start with
        // "org.hibernate.collection." so the resolver must skip them.
        // Hibernate's PersistentBag / PersistentSet / PersistentMap is what
        // the resolver actually targets.
        assertNull(resolver.resolve(List.of(1, 2, 3)));
        assertNull(resolver.resolve(new HashSet<>(List.of("a", "b"))));
        assertNull(resolver.resolve(new HashMap<>()));
    }

    @Test
    void unrelatedClassWithDollarSignsIsNotResolved() {
        // The proxy detector looks for the literal substring
        // "$HibernateProxy$". A class with $ in its name from a different
        // tool (CGLIB, JDK Proxy, lambdas) must NOT be misclassified.
        Object lambdaInstance = (Runnable) () -> {};
        assertNull(resolver.resolve(lambdaInstance));
    }
}
