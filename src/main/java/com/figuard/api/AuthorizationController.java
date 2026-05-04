package com.figuard.api;

import com.figuard.api.dto.request.AuthorizeSpendRequest;
import com.figuard.api.dto.response.AuthorizationResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.validation.Valid;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/api/v1/authorize")
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;
    private final MeterRegistry meterRegistry;

    private Timer authorizeLatency;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
        authorizeLatency = Timer.builder("figuard.authorize.latency")
            .description("Latency of POST /authorize calls")
            .publishPercentiles(0.50, 0.99)
            .register(meterRegistry);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public AuthorizationResponse authorize(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AuthorizeSpendRequest request) {

        // TokenRedactionFilter wraps the request and returns [REDACTED] for X-Session-Token.
        // Unwrap to get the original request so we can hash the real token value.
        HttpServletRequest original = httpRequest;
        while (original instanceof HttpServletRequestWrapper wrapper) {
            original = (HttpServletRequest) wrapper.getRequest();
        }
        String sessionToken = original.getHeader("X-Session-Token");

        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Session-Token header is required");
        }

        long startNs = System.nanoTime();
        AuthorizationResponse response = authorizationService.authorize(sessionToken, request, TenantContext.get());
        long latencyNs = System.nanoTime() - startNs;
        long latencyMs = latencyNs / 1_000_000;

        // Record latency histogram
        authorizeLatency.record(latencyNs, java.util.concurrent.TimeUnit.NANOSECONDS);

        // Increment decision counters
        String decision = response.getDecision().name();
        if ("AUTHORIZED".equals(decision)) {
            Counter.builder("figuard.authorize.approved").register(meterRegistry).increment();
        } else if ("DENIED".equals(decision)) {
            String reason = response.getDenialReason() != null ? response.getDenialReason().name() : "UNKNOWN";
            Counter.builder("figuard.authorize.denied")
                .tag("denial_reason", reason)
                .register(meterRegistry)
                .increment();
        }

        logDecision(request, response, latencyMs);
        return response;
    }

    // Emits a single structured log line for every authorize decision.
    // All fields land as top-level JSON keys in the LogstashEncoder output alongside traceId.
    // Extra MDC keys are removed immediately after logging — they must not leak to other log lines.
    private void logDecision(AuthorizeSpendRequest request, AuthorizationResponse response, long latencyMs) {
        try {
            MDC.put("event", "authorize_decision");
            if (response.getBudgetSnapshot() != null && response.getBudgetSnapshot().getId() != null) {
                MDC.put("budgetId", response.getBudgetSnapshot().getId().toString());
            }
            MDC.put("agentId", request.getAgentId());
            MDC.put("requestedAmount", request.getRequestedAmount().toPlainString());
            MDC.put("decision", response.getDecision().name());
            if (response.getAllocationSnapshot() != null) {
                MDC.put("allocationCategory", response.getAllocationSnapshot().getCategory());
            }
            if (response.getDenialReason() != null) {
                MDC.put("denialReason", response.getDenialReason().name());
            }
            MDC.put("latencyMs", String.valueOf(latencyMs));
            log.info("authorize_decision");
        } finally {
            MDC.remove("event");
            MDC.remove("budgetId");
            MDC.remove("agentId");
            MDC.remove("requestedAmount");
            MDC.remove("decision");
            MDC.remove("allocationCategory");
            MDC.remove("denialReason");
            MDC.remove("latencyMs");
        }
    }
}
