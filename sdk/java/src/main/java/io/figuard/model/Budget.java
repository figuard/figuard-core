package io.figuard.model;

import java.math.BigDecimal;
import java.util.List;

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
        String currency,
        BigDecimal amountSpent,
        BigDecimal amountReserved,
        BigDecimal availableAmount,
        String status,
        String expiresAt,
        String createdAt,
        String sessionTokenPrefix,
        List<AllocationResponse> allocations,
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
}
