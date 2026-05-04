package com.figuard.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

// Must run BEFORE ApiKeyAuthFilter so no downstream component ever sees raw credentials.
@Component
@Order(1)
public class TokenRedactionFilter implements Filter {

    private static final Set<String> REDACTED_HEADERS = Set.of(
        "x-session-token",
        "x-agent-budget-key"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            chain.doFilter(new RedactingRequestWrapper(httpRequest), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    static class RedactingRequestWrapper extends HttpServletRequestWrapper {

        RedactingRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (REDACTED_HEADERS.contains(name.toLowerCase())) return "[REDACTED]";
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (REDACTED_HEADERS.contains(name.toLowerCase())) {
                return Collections.enumeration(Collections.singleton("[REDACTED]"));
            }
            return super.getHeaders(name);
        }

        // Header names remain visible — only values are redacted
        @Override
        public Enumeration<String> getHeaderNames() {
            return super.getHeaderNames();
        }
    }
}
