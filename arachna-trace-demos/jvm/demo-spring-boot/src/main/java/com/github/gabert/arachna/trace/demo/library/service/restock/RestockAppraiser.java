package com.github.gabert.arachna.trace.demo.library.service.restock;

import com.github.gabert.arachna.trace.demo.library.service.BookSO;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Replacement-cost appraisal for the restock-quote demo — in <b>two code
 * versions</b>, standing in for a change an AI coding agent might produce.
 *
 * <p>The {@code classic} version handles legacy (pre-ISBN) identifiers with
 * an explicit branch and rounds HALF_UP. The {@code refactored} version is
 * the kind of "simplification" agents love to write: it unifies the rarity
 * lookup into one try/catch — silently swallowing the legacy-identifier
 * exception and losing the vintage premium — and switches rounding to
 * HALF_EVEN. Both edits look harmless in a code diff; both change output.
 * The audit demo records one session per version and shows the difference
 * on the Behavior diff screen, and the swallowed exception on the Flow
 * narrative.</p>
 *
 * <p>The two versions live behind a runtime switch (rather than two branches
 * of the repository) so the demo can record both in one build; from the
 * trace's point of view the calls are identical-signature methods with
 * different behavior — exactly what two commits would produce.</p>
 */
public class RestockAppraiser {

    private final boolean refactored;

    public RestockAppraiser(boolean refactored) {
        this.refactored = refactored;
    }

    public boolean isRefactored() {
        return refactored;
    }

    public BigDecimal appraise(BookSO book) {
        BigDecimal base = baseCost(book.getYear());
        BigDecimal rarity = rarityFor(book);
        return round(base.multiply(rarity));
    }

    private BigDecimal rarityFor(BookSO book) {
        if (!refactored) {
            // classic: legacy identifiers (pre-ISBN era) get an explicit
            // vintage-premium branch; checkDigitOf is only reached for
            // well-formed ISBN-13s.
            return isIsbn13(book.getIsbn())
                    ? modernRarity(book.getIsbn())
                    : vintagePremium(book.getYear());
        }
        // refactored: "unified" lookup. checkDigitOf throws for legacy
        // identifiers; the catch swallows it and the vintage premium is
        // silently replaced by the fallback. The trace shows the exception
        // on checkDigitOf while this call returns normally.
        try {
            return modernRarity(book.getIsbn());
        } catch (IllegalArgumentException e) {
            return fallbackRarity(book.getIsbn());
        }
    }

    private BigDecimal modernRarity(String isbn) {
        int check = checkDigitOf(isbn);
        return BigDecimal.ONE.add(
                BigDecimal.valueOf(check).divide(BigDecimal.valueOf(20)));
    }

    private int checkDigitOf(String isbn) {
        String digits = isbn.replace("-", "");
        if (digits.length() != 13
                || !(digits.startsWith("978") || digits.startsWith("979"))
                || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Not an ISBN-13: " + isbn);
        }
        return digits.charAt(12) - '0';
    }

    private boolean isIsbn13(String isbn) {
        String digits = isbn.replace("-", "");
        return digits.length() == 13
                && (digits.startsWith("978") || digits.startsWith("979"))
                && digits.chars().allMatch(Character::isDigit);
    }

    /** Classic-only: premium for pre-ISBN-era editions. */
    private BigDecimal vintagePremium(int year) {
        return year < 1950 ? new BigDecimal("2.5") : new BigDecimal("1.6");
    }

    /** Refactored-only: the silent default that replaced the premium. */
    private BigDecimal fallbackRarity(String isbn) {
        return BigDecimal.ONE;
    }

    private BigDecimal baseCost(int year) {
        if (year < 1950) return new BigDecimal("80.00");
        if (year < 1980) return new BigDecimal("40.05");
        return new BigDecimal("25.50");
    }

    private BigDecimal round(BigDecimal value) {
        // classic: HALF_UP. refactored: HALF_EVEN — a one-word "cleanup"
        // that moves every cost landing exactly on half a cent.
        return value.setScale(2, refactored ? RoundingMode.HALF_EVEN : RoundingMode.HALF_UP);
    }
}
