package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

public class TestJpaProxyResolverBeta implements JpaProxyResolver {

    @Override
    public String name() {
        return "beta";
    }

    @Override
    public Object resolve(Object proxy) {
        return proxy;
    }
}
