package com.github.gabert.arachna.trace.spi.session.spring;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Captures the HTTP session ID at the start of every request and clears it
 * at the end. {@link SpringSessionIdResolver} reads from the resulting
 * thread-local to satisfy the agent's session-id lookup.
 *
 * <p>Pure Jakarta Servlet — works in any servlet container (Spring Boot,
 * Quarkus servlet, plain Tomcat, etc.). Register it however your container
 * wants:</p>
 *
 * <ul>
 *   <li><b>Spring Boot:</b>
 *     <pre>{@code @Bean
 * public SessionIdFilter sessionIdFilter() {
 *     return new SessionIdFilter();
 * }
 * }</pre>
 *     (Spring auto-detects Filter beans and adds them to the chain.)
 *   </li>
 *   <li><b>Plain servlet:</b> register via {@code web.xml} or
 *     {@code ServletContext.addFilter(...)}.</li>
 * </ul>
 *
 * <p>The filter is intentionally not annotated with {@code @Component} or
 * any Spring Boot auto-configuration glue: registration is the user's
 * responsibility so it stays explicit and visible in their app.</p>
 */
public class SessionIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String sessionId = httpRequest.getSession().getId();
            SessionIdHolder.set(sessionId);
            chain.doFilter(request, response);
        } finally {
            SessionIdHolder.clear();
        }
    }
}
