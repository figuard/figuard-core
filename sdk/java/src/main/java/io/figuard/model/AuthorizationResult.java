package io.figuard.model;

import io.figuard.exception.FiGuardDeniedException;

import java.math.BigDecimal;

/**
 * Returned by {@code FiGuardClient.authorize()}.
 *
 * <p>Use {@code isAuthorized()} to check outcome, or chain {@code raiseIfDenied()}
 * for exception-driven control flow:
 * <pre>{@code
 * AuthorizationResult result = client.authorize(request).raiseIfDenied();
 * client.confirmEvent(result.eventId(), confirmedAmount);
 * }</pre>
 */
public record AuthorizationResult(
        String eventId,
        String decision,
        BigDecimal approvedAmount,
        String authorizedAt,
        BudgetSnapshot budgetSnapshot,
        AllocationSnapshot allocationSnapshot,
        String denialReason,
        String denialMessage,
        /** Set when denialReason is ENTITY_ALREADY_AUTHORIZED. */
        String originalEventId,
        String originalEventStatus
) {
    /** True when the decision is AUTHORIZED. */
    public boolean isAuthorized() {
        return "AUTHORIZED".equals(decision);
    }

    /**
     * Returns {@code this} if authorized; throws {@link FiGuardDeniedException} if denied.
     * Designed for fluent chaining:
     * <pre>{@code
     * var result = client.authorize(request).raiseIfDenied();
     * }</pre>
     */
    public AuthorizationResult raiseIfDenied() {
        if (!isAuthorized()) {
            throw new FiGuardDeniedException(
                    denialReason != null ? denialReason : "UNKNOWN",
                    denialMessage,
                    originalEventId
            );
        }
        return this;
    }
}
