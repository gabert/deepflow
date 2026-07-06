package com.github.gabert.arachna.trace.demo.library.service.restock;

import java.math.BigDecimal;

public class RestockLineSO {

    private final Long bookId;
    private final String title;
    private final BigDecimal replacementCost;

    public RestockLineSO(Long bookId, String title, BigDecimal replacementCost) {
        this.bookId = bookId;
        this.title = title;
        this.replacementCost = replacementCost;
    }

    public Long getBookId() { return bookId; }
    public String getTitle() { return title; }
    public BigDecimal getReplacementCost() { return replacementCost; }
}
