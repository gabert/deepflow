package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

public class TestJpaProxyResolverGamma implements JpaProxyResolver {

    @Override
    public String name() {
        return "gamma";
    }

    @Override
    public Object resolve(Object proxy) {
        return proxy;
    }
}
