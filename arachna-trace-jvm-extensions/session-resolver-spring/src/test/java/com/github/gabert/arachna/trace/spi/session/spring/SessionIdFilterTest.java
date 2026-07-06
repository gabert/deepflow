package com.github.gabert.arachna.trace.spi.session.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pin the filter's two responsibilities:
 *   1. populate {@link SessionIdHolder} for the duration of the request
 *   2. clear it on exit (even when the downstream chain throws)
 *
 * Mockito stands in for the servlet container so we can run the filter
 * outside a real Tomcat / Jetty / etc.
 */
class SessionIdFilterTest {

    @AfterEach
    void cleanup() {
        SessionIdHolder.clear();
    }

    @Test
    void holderCarriesTheSessionIdDuringTheChainCall() throws IOException, ServletException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession()).thenReturn(session);
        when(session.getId()).thenReturn("session-xyz");

        AtomicReference<String> observedDuringChain = new AtomicReference<>();
        FilterChain chain = (r, resp) -> observedDuringChain.set(SessionIdHolder.get());

        new SessionIdFilter().doFilter(req, mock(ServletResponse.class), chain);

        assertEquals("session-xyz", observedDuringChain.get(),
                "downstream chain must see the session id in the holder");
    }

    @Test
    void holderIsClearedAfterTheRequest() throws IOException, ServletException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession()).thenReturn(session);
        when(session.getId()).thenReturn("session-xyz");

        new SessionIdFilter().doFilter(req, mock(ServletResponse.class), (r, resp) -> {});

        assertNull(SessionIdHolder.get(),
                "filter must clear the holder so the value does not leak to the next request on the same thread");
    }

    @Test
    void holderIsClearedEvenWhenChainThrows() {
        // The whole point of the try/finally in doFilter: an exception in
        // downstream handling must not leave the ThreadLocal populated.
        // Otherwise this thread's NEXT request would inherit a stale id.
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession()).thenReturn(session);
        when(session.getId()).thenReturn("session-xyz");

        FilterChain throwingChain = (r, resp) -> { throw new ServletException("kaboom"); };

        assertThrows(ServletException.class,
                () -> new SessionIdFilter().doFilter(req, mock(ServletResponse.class), throwingChain));

        assertNull(SessionIdHolder.get(),
                "holder must be cleared even when the chain throws");
    }
}
