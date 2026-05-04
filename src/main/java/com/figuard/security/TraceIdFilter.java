package com.figuard.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Runs first in the filter chain (Order=0, before TokenRedactionFilter at Order=1).
 * Assigns a UUID traceId to every request, puts it in MDC so it appears in every log line,
 * and returns it in the X-Trace-Id response header so callers can correlate client-side.
 * MDC is cleared in finally to ensure the thread pool does not leak trace context.
 */
@Component
@Order(0)
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_KEY, traceId);
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader(TRACE_ID_HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
