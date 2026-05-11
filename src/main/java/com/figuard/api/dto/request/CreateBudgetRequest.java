package com.figuard.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class CreateBudgetRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    private String externalReference;

    @Size(max = 1000)
    private String intentContext;

    private List<String> intentTags;

    @NotNull(message = "totalLimit is required")
    @Positive(message = "totalLimit must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal totalLimit;

    // Monetary budgets: 3-letter ISO code (e.g. "USD"). Exactly one of currency or unit must be set.
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    private String currency;

    // Resource budgets: free-form label (e.g. "tokens", "api_calls", "gpu_hours").
    // Exactly one of currency or unit must be set.
    @Size(max = 50, message = "unit must be 50 characters or fewer")
    private String unit;

    @AssertTrue(message = "exactly one of currency or unit must be set")
    private boolean isCurrencyOrUnitValid() {
        boolean hasCurrency = currency != null && !currency.isBlank();
        boolean hasUnit = unit != null && !unit.isBlank();
        return hasCurrency ^ hasUnit;
    }

    @Positive
    @Digits(integer = 15, fraction = 4)
    private BigDecimal softLimit;

    // Optional per-transaction ceiling. When set, any single authorize request
    // with requestedQuantity > maxTransactionQuantity is denied (EXCEEDS_QUANTITY_LIMIT).
    // Must be <= totalLimit if both are set — validated at the service layer.
    @Positive
    @Digits(integer = 15, fraction = 4)
    private BigDecimal maxTransactionQuantity;

    // Optional. When set, AUTHORIZED events older than this many seconds are
    // excluded from the available-quantity calculation (lazy auto-expiry).
    // Eliminates orphaned reservations without a background sweep job.
    @Positive(message = "authorizationExpirySeconds must be positive")
    private Integer authorizationExpirySeconds;

    @NotNull(message = "expiresAt is required")
    @Future(message = "expiresAt must be in the future")
    private OffsetDateTime expiresAt;

    private boolean entityDedupEnabled = false;

    private boolean anomalyDetectionEnabled = false;

    // Anomaly detection tuning — only used when anomalyDetectionEnabled=true.
    // If omitted, AgentBudget defaults apply (multiplier=3.0, minSamples=5).
    // Sandbox seed script must pass anomalyMinSampleSize=3 explicitly — with
    // only 8 bills and 4 confirmations, the default of 5 prevents the anomaly
    // scenario from ever firing.
    @Positive
    @Digits(integer = 5, fraction = 2)
    private BigDecimal anomalyPauseThresholdMultiplier;

    @Positive
    @Max(value = 1000, message = "anomalyMinSampleSize must be <= 1000")
    private Integer anomalyMinSampleSize;

    @Valid
    private List<AllocationRequest> allocations;

    private Map<String, Object> metadata;
}
