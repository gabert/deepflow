package com.github.gabert.deepflow.jpaproxy.hibernate;

import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves Hibernate proxy objects and collection wrappers to their
 * underlying real objects.
 *
 * Uses reflection so this module has no compile-time Hibernate dependency.
 * At runtime, the application's classpath provides Hibernate.
 *
 * Handles two cases:
 *
 * 1. Entity proxies (e.g. AuthorEntity$HibernateProxy$...)
 *    Unwrapped via getHibernateLazyInitializer().getImplementation().
 *
 * 2. Collection wrappers (PersistentBag, PersistentSet, PersistentMap)
 *    Copied to plain Java collections (ArrayList, LinkedHashSet, LinkedHashMap).
 *
 * In both cases, if the Hibernate session is closed and the proxy/collection
 * is not initialized, returns {@code null} and the agent falls back to its
 * default behavior.
 *
 * Activate via: {@code jpa_proxy_resolver=hibernate}
 */
public final class HibernateJpaProxyResolver implements JpaProxyResolver {

    @Override
    public String name() {
        return "hibernate";
    }

    @Override
    public Object resolve(Object obj) {
        String className = obj.getClass().getName();

        if (className.contains("$HibernateProxy$")) {
            return resolveEntityProxy(obj);
        }

        if (className.startsWith("org.hibernate.collection.")) {
            return resolveCollectionWrapper(obj);
        }

        return null;
    }

    private Object resolveEntityProxy(Object proxy) {
        try {
            Method getInitializer = proxy.getClass().getMethod("getHibernateLazyInitializer");
            Object initializer = getInitializer.invoke(proxy);
            Method getImplementation = initializer.getClass().getMethod("getImplementation");
            return getImplementation.invoke(initializer);
        } catch (Exception e) {
            return null;
        }
    }

    private Object resolveCollectionWrapper(Object wrapper) {
        if (wrapper instanceof List<?> list) return new ArrayList<>(list);
        if (wrapper instanceof Set<?> set)   return new LinkedHashSet<>(set);
        if (wrapper instanceof Map<?, ?> map) return new LinkedHashMap<>(map);
        return null;
    }
}
