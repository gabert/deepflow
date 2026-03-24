package com.github.gabert.deepflow.demo.library.session;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
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
