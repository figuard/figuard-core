package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.domain.enums.BudgetStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// traceId is included NON_NULL — absent on responses where MDC was not populated

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class BudgetResponse {

    private UUID id;
    private String userId;
    private String externalReference;
    private String intentContext;
    private List<String> intentTags;

    // Populated ONCE at creation time only. Null on all subsequent reads.
    // For simple budgets: one entry with category="default".
    // For entitlement-backed budgets: one entry per entitlement item.
    // The caller must store tokens immediately — they cannot be retrieved again.
    private List<BudgetTokenResponse> tokens;

    private BigDecimal totalLimit;
    private BigDecimal maxTransactionQuantity;
    private String currency;
    private String unit;
    private BigDecimal quantitySpent;
    private BigDecimal quantityReserved;
    private BigDecimal availableQuantity;
    private BigDecimal softLimit;
    private Integer authorizationExpirySeconds;
    private Integer velocityMaxPerMinute;
    private BigDecimal velocityMaxAmountPerHour;
    private Integer velocityMaxPerDay;
    private boolean anomalyDetectionEnabled;
    private boolean autoPauseOnAnomaly;
    private BudgetStatus status;
    private List<AllocationResponse> allocations;
    private OffsetDateTime expiresAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime createdAt;
    private Map<String, Object> metadata;
    private String traceId;
}
