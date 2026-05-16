package io.figuard.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Represents an agent budget returned by the FiGuard API.
 *
 * <p>{@code tokens} is only present immediately after {@code createBudget()} —
 * store the session token securely. It is {@code null} on all subsequent reads.
 *
 * <p>Use {@link #primaryToken()} for the common single-token case; for
 * entitlement-backed budgets iterate {@code tokens} directly.
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
        String intentContext,
        List<String> intentTags,
        String externalReference,
        BigDecimal softLimit,
        BigDecimal maxTransactionQuantity,
        Integer authorizationExpirySeconds,
        List<AllocationResponse> allocations,
        Map<String, Object> metadata,
        String traceId,
        /**
         * Only present on createBudget() response. Null on all subsequent reads.
         * For simple budgets contains one entry with category="default".
         * For entitlement-backed budgets contains one entry per entitlement item.
         */
        List<BudgetToken> tokens
) {
    /**
     * Convenience accessor for the common single-token case.
     *
     * @return the first {@link BudgetToken} when {@code tokens} is non-empty, otherwise {@code null}.
     */
    public BudgetToken primaryToken() {
        return tokens != null && !tokens.isEmpty() ? tokens.get(0) : null;
    }

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

    /**
     * A single session token entry returned within the {@code tokens} list on budget creation.
     *
     * @param category           "default" for simple budgets; entitlement item name for entitlement-backed budgets.
     * @param sessionToken       Full session token value. Only present on createBudget() — never returned again.
     * @param sessionTokenPrefix Short prefix of the session token (safe to store/display).
     * @param unit               Resource unit label for resource budgets; null for monetary budgets.
     * @param currency           ISO 4217 currency code for monetary budgets; null for resource budgets.
     */
    public record BudgetToken(
            String category,
            String sessionToken,
            String sessionTokenPrefix,
            String unit,
            String currency
    ) {}
}
