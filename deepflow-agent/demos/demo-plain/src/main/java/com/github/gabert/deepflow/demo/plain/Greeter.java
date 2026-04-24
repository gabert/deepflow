package com.github.gabert.deepflow.demo.plain;

import java.util.List;

public class Greeter {

    public String greet(String name) {
        String decorated = decorate(name);
        return "Hello, " + decorated + "!";
    }

    private String decorate(String name) {
        return "[" + name.toUpperCase() + "]";
    }

    public void sneakyMutate(List<String> items) {
        items.add("sneaky");
        items.set(0, "CHANGED");
    }
}
