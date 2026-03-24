package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

public class TestJpaProxyResolverAlpha implements JpaProxyResolver {

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public Object resolve(Object proxy) {
        return proxy;
    }
}
