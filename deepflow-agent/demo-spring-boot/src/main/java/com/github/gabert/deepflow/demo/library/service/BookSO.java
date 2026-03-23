package com.github.gabert.deepflow.demo.library.service;

public class BookSO {

    private final Long id;
    private final String title;
    private final String isbn;
    private final int year;
    private final Long authorId;
    private final String authorName;

    public BookSO(Long id, String title, String isbn, int year, Long authorId, String authorName) {
        this.id = id;
        this.title = title;
        this.isbn = isbn;
        this.year = year;
        this.authorId = authorId;
        this.authorName = authorName;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getIsbn() { return isbn; }
    public int getYear() { return year; }
    public Long getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
}
