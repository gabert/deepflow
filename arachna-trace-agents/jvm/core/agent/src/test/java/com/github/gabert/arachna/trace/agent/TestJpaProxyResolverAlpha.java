package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

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
