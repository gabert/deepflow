package com.github.gabert.deepflow.agent.session.config;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

/**
 * Resolver that reads the session ID from the system property
 * {@code deepflow.session_id}, which is published by the agent
 * from the {@code session_id} config property.
 *
 * Activate via: {@code session_resolver=config}
 *
 * Example config:
 * <pre>
 * session_resolver=config
 * session_id=my-debug-run-01
 * </pre>
 */
public final class ConfigSessionIdResolver implements SessionIdResolver {

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String resolve() {
        return System.getProperty("deepflow.session_id");
    }
}
