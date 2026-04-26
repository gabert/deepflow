package com.github.gabert.deepflow.agent.session.config;

import com.github.gabert.deepflow.agent.session.SessionIdResolver;

import java.util.Map;

/**
 * Resolver that reads the session ID directly from the agent config map.
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

    private volatile String sessionId;

    @Override
    public String name() {
        return "config";
    }

    @Override
    public void init(Map<String, String> config) {
        this.sessionId = config.get("session_id");
    }

    @Override
    public String resolve() {
        return sessionId;
    }
}
