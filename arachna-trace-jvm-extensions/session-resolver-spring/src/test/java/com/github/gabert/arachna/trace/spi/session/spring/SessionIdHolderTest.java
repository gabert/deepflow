package com.github.gabert.arachna.trace.spi.session.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pin the ThreadLocal lifecycle that {@link SessionIdFilter} depends on:
 * set, read, clear. Each test cleans up after itself so a leaked value
 * cannot bleed into the next.
 */
class SessionIdHolderTest {

    @AfterEach
    void cleanup() {
        SessionIdHolder.clear();
    }

    @Test
    void getReturnsNullWhenNothingSet() {
        // Before any request has run through the filter the holder is empty.
        // Resolver code should be tolerant of this — null = no session.
        assertNull(SessionIdHolder.get());
    }

    @Test
    void setThenGetReturnsTheSetValue() {
        SessionIdHolder.set("abc-123");

        assertEquals("abc-123", SessionIdHolder.get());
    }

    @Test
    void clearRemovesTheValue() {
        SessionIdHolder.set("abc-123");
        SessionIdHolder.clear();

        assertNull(SessionIdHolder.get());
    }

    @Test
    void valuesDoNotLeakAcrossThreads() throws InterruptedException {
        // The whole point of the ThreadLocal is per-request isolation. Pin
        // it: a value set on this thread must not be visible on another.
        SessionIdHolder.set("thread-1");

        String[] otherThreadObserved = {"NOT_RUN"};
        Thread other = new Thread(() -> otherThreadObserved[0] = SessionIdHolder.get());
        other.start();
        other.join();

        assertNull(otherThreadObserved[0],
                "value set on this thread leaked into another thread");
    }
}
