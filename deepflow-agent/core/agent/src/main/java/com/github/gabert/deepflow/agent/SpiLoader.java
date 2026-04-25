package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;
import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

import java.util.ServiceLoader;
import java.util.function.Function;

final class SpiLoader {

    private SpiLoader() {}

    static final SessionIdResolver NOOP_RESOLVER = new SessionIdResolver() {
        @Override public String name() { return "noop"; }
        @Override public String resolve() { return null; }
    };

    static SessionIdResolver loadSessionIdResolver(AgentConfig config, ClassLoader classLoader) {
        String name = config.getSessionResolver();
        if (name == null) {
            System.err.println("[DeepFlow] SessionIdResolver: no session_resolver configured, using built-in noop");
            return NOOP_RESOLVER;
        }
        SessionIdResolver found = loadByName(SessionIdResolver.class, SessionIdResolver::name,
                name, "SessionIdResolver", classLoader);
        if (found != null) return found;
        System.err.println("[DeepFlow] WARNING: session_resolver='" + name
                + "' not found on classpath, session tracking disabled");
        return NOOP_RESOLVER;
    }

    static JpaProxyResolver loadJpaProxyResolver(AgentConfig config, ClassLoader classLoader) {
        String name = config.getJpaProxyResolver();
        if (name == null) {
            System.err.println("[DeepFlow] JpaProxyResolver: no jpa_proxy_resolver configured, proxy unwrapping disabled");
            return null;
        }
        JpaProxyResolver found = loadByName(JpaProxyResolver.class, JpaProxyResolver::name,
                name, "JpaProxyResolver", classLoader);
        if (found != null) return found;
        System.err.println("[DeepFlow] WARNING: jpa_proxy_resolver='" + name
                + "' not found on classpath, JPA proxy resolution disabled");
        return null;
    }

    static ClassLoader resolveClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    private static <T> T loadByName(Class<T> spiType, Function<T, String> nameGetter,
                                     String name, String label, ClassLoader classLoader) {
        ServiceLoader<T> loader = ServiceLoader.load(spiType, classLoader);
        T selected = null;
        System.err.println("[DeepFlow] " + label + ": looking for '" + name + "'");
        for (T candidate : loader) {
            String candidateName = nameGetter.apply(candidate);
            System.err.println("[DeepFlow] " + label + ": found '" + candidateName
                    + "' (" + candidate.getClass().getName() + ")");
            if (name.equals(candidateName)) {
                selected = candidate;
            }
        }
        if (selected != null) {
            System.err.println("[DeepFlow] " + label + ": activated '" + nameGetter.apply(selected) + "'");
        }
        return selected;
    }
}
