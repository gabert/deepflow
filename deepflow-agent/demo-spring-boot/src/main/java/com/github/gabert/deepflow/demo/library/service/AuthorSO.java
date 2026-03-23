package com.github.gabert.deepflow.demo.library.service;

public class AuthorSO {

    private final Long id;
    private final String name;

    public AuthorSO(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
}
