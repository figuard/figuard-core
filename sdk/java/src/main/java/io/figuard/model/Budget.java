package io.figuard.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Represents an agent budget returned by the FiGuard API.
 *
 * <p>{@code sessionToken} is only present immediately after {@code createBudget()} —
 * store it securely. It is {@code null} on all subsequent reads.
 */
public record Budget(
        String id,
        String userId,
        BigDecimal totalLimit,
        /** ISO 4217 currency code. Null for resource budgets. */
        String currency,
        /** Resource unit label (e.g. "tokens", "api_calls"). Null for monetary budgets. */
        String unit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity,
        String status,
        String expiresAt,
        String createdAt,
        String cancelledAt,
        String sessionTokenPrefix,
        String intentContext,
        List<String> intentTags,
        String externalReference,
        BigDecimal softLimit,
        BigDecimal maxTransactionQuantity,
        Integer authorizationExpirySeconds,
        List<AllocationResponse> allocations,
        Map<String, Object> metadata,
        String traceId,
        /** Only present on createBudget() response. Null on all subsequent reads. */
        String sessionToken
) {
    /** True when status is ACTIVE. */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** True when status is PAUSED (anomaly detected, awaiting manual resume). */
    public boolean isPaused() {
        return "PAUSED".equals(status);
    }

    /** True for currency-based budgets; false for resource budgets. */
    public boolean isMonetary() {
        return currency != null && !currency.isBlank();
    }
}
