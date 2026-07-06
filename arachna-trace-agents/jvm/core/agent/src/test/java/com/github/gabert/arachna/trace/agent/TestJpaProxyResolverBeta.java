package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

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
