package com.github.gabert.deepflow.demo.library.repository;

public class AuthorDTO {

    private final Long id;
    private final String name;

    public AuthorDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
}
