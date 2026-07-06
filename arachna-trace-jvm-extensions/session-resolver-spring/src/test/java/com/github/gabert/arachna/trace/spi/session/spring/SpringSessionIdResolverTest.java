package com.github.gabert.arachna.trace.spi.session.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pin the resolver's contract: it is a thin reader on top of
 * {@link SessionIdHolder}. All logic about *populating* the holder lives
 * in {@link SessionIdFilter}; the resolver itself is supposed to be
 * dumb.
 */
class SpringSessionIdResolverTest {

    @AfterEach
    void cleanup() {
        SessionIdHolder.clear();
    }

    @Test
    void nameIsSpringSession() {
        // The agent matches `session_resolver=<value>` against this. Pin it.
        assertEquals("spring-session", new SpringSessionIdResolver().name());
    }

    @Test
    void resolveReturnsWhateverHolderHas() {
        SessionIdHolder.set("session-from-request");

        assertEquals("session-from-request", new SpringSessionIdResolver().resolve());
    }

    @Test
    void resolveReturnsNullWhenNoRequestInFlight() {
        // Outside of a servlet request (e.g. background thread, or before
        // the filter has run on the current thread), the holder is empty.
        // The resolver must surface that as null, not throw.
        assertNull(new SpringSessionIdResolver().resolve());
    }
}
