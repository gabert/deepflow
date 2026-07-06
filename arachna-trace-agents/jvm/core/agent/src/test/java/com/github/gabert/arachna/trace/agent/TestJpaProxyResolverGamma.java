package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

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
