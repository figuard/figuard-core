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
    // The caller must store this immediately — it cannot be retrieved again.
    private String sessionToken;
    private String sessionTokenPrefix;

    private BigDecimal totalLimit;
    private BigDecimal maxTransactionQuantity;
    private String currency;
    private String unit;
    private BigDecimal quantitySpent;
    private BigDecimal quantityReserved;
    private BigDecimal availableQuantity;
    private BigDecimal softLimit;
    private Integer authorizationExpirySeconds;
    private boolean anomalyDetectionEnabled;
    private boolean autoPauseOnAnomaly;
    private BudgetStatus status;
    private List<AllocationResponse> allocations;
    private OffsetDateTime expiresAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime createdAt;
    private Map<String, Object> metadata;
}
