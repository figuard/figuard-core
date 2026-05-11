package com.figuard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * CORS filter — runs before ApiKeyAuthFilter (@Order 2) so that OPTIONS
 * preflight requests get the correct CORS headers and a 200 response
 * without triggering the API key check.
 *
 * Allowed origins are configured via:
 *   agent-billing.cors.allowed-origins (comma-separated list)
 *
 * Defaults to localhost:5173 (Vite dev server) for local development.
 */
@Component
@Order(1)
public class CorsConfig extends OncePerRequestFilter {

    @Value("${agent-billing.cors.allowed-origins:http://localhost:5173}")
    private String allowedOriginsRaw;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");

        if (origin != null && isAllowed(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers",
                    "Content-Type, X-Agent-Budget-Key, X-Session-Token, Authorization");
            response.setHeader("Access-Control-Max-Age", "3600");
        }

        // Short-circuit OPTIONS preflight — no further filters needed
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowed(String origin) {
        List<String> allowed = List.of(allowedOriginsRaw.split(","));
        return allowed.stream()
                .map(String::trim)
                .anyMatch(o -> o.equals("*") || o.equalsIgnoreCase(origin));
    }
}
