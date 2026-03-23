package com.github.gabert.deepflow.proxy.hibernate;

import com.github.gabert.deepflow.proxy.ProxyResolver;

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
 * Activate via: {@code proxy_resolver=hibernate}
 */
public final class HibernateProxyResolver implements ProxyResolver {

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object resolveCollectionWrapper(Object wrapper) {
        try {
            if (wrapper instanceof List)  return new ArrayList<>((List) wrapper);
            if (wrapper instanceof Set)   return new LinkedHashSet<>((Set) wrapper);
            if (wrapper instanceof Map)   return new LinkedHashMap<>((Map) wrapper);
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
