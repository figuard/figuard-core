package com.figuard.security;

import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.util.HashUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Agent-Budget-Key";

    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Actuator: reachable by load balancers without an API key
        // /receipts/**: public receipt pages — no auth needed to view a shared receipt
        // /internal/demo/**: demo seed endpoint — no key exists yet when this is called
        return uri.startsWith("/actuator") || uri.startsWith("/receipts/") || uri.startsWith("/internal/demo/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // TokenRedactionFilter has already wrapped the request — read from the original
        // attribute we stash below, not from the wrapper.
        String rawKey = getOriginalHeader(request);

        if (rawKey == null || rawKey.isBlank()) {
            unauthorized(response, "Missing " + API_KEY_HEADER + " header");
            return;
        }

        String keyHash = sha256(rawKey);

        var apiKeyOpt = apiKeyRepository.findByKeyHash(keyHash);
        if (apiKeyOpt.isEmpty() || !apiKeyOpt.get().isActive()) {
            unauthorized(response, "Invalid or inactive API key");
            return;
        }

        var apiKey = apiKeyOpt.get();
        TenantContext.set(apiKey.getTenant());

        // Fire-and-forget lastUsedAt update — best effort, don't fail the request if this fails
        try {
            apiKey.setLastUsedAt(OffsetDateTime.now());
            apiKeyRepository.save(apiKey);
        } catch (Exception e) {
            log.warn("Failed to update lastUsedAt for key prefix={}", apiKey.getKeyPrefix());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();   // always clear — thread goes back to pool after this
        }
    }

    // The request reaching this filter is already wrapped by TokenRedactionFilter,
    // so request.getHeader(API_KEY_HEADER) returns "[REDACTED]".
    // We reach into the underlying real request via getAttribute to get the original value.
    // The real header is read from the original HttpServletRequest before wrapping.
    private String getOriginalHeader(HttpServletRequest request) {
        // Unwrap to get original request
        jakarta.servlet.ServletRequest current = request;
        while (current instanceof jakarta.servlet.http.HttpServletRequestWrapper wrapper) {
            if (current instanceof TokenRedactionFilter.RedactingRequestWrapper) {
                current = wrapper.getRequest();
            } else {
                break;
            }
        }
        if (current instanceof HttpServletRequest original) {
            return original.getHeader(API_KEY_HEADER);
        }
        return request.getHeader(API_KEY_HEADER);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    public static String sha256(String input) {
        return HashUtil.sha256(input);
    }
}
