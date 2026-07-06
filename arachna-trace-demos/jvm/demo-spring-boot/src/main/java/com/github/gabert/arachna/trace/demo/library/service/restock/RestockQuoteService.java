package com.github.gabert.arachna.trace.demo.library.service.restock;

import com.github.gabert.arachna.trace.demo.library.service.BookSO;
import com.github.gabert.arachna.trace.demo.library.service.LibraryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restock quote: what replacing an author's catalog would cost. The demo
 * flow for the AI-code-audit workflows — the appraisal logic exists in two
 * versions (see {@link RestockAppraiser}) selected by the
 * {@code library.restock.policy} property ({@code classic} default,
 * {@code refactored}), so the same scenario recorded twice yields two
 * sessions the Behavior diff screen can compare.
 */
@Service
public class RestockQuoteService {

    private final LibraryService libraryService;
    private final RestockAppraiser appraiser;

    public RestockQuoteService(LibraryService libraryService,
                               @Value("${library.restock.policy:classic}") String policy) {
        this.libraryService = libraryService;
        this.appraiser = new RestockAppraiser("refactored".equals(policy));
    }

    public Map<String, Object> quoteForAuthor(Long authorId) {
        List<BookSO> books = libraryService.booksByAuthor(authorId);
        List<RestockLineSO> lines = new ArrayList<>();
        for (BookSO book : books) {
            lines.add(new RestockLineSO(book.getId(), book.getTitle(), appraiser.appraise(book)));
        }
        return summarize(lines);
    }

    private Map<String, Object> summarize(List<RestockLineSO> lines) {
        if (appraiser.isRefactored()) {
            // refactored-only "presentation improvement": orders the quote
            // lines by cost — by sorting the caller's list in place. The
            // trace shows it as an AR≠AX argument mutation on this call.
            lines.sort(Comparator.comparing(RestockLineSO::getReplacementCost).reversed());
        }
        BigDecimal total = lines.stream()
                .map(RestockLineSO::getReplacementCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("lines", lines);
        quote.put("total", total);
        return quote;
    }
}
