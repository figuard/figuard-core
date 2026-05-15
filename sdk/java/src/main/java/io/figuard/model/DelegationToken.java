package io.figuard.model;

import java.util.List;

/**
 * A scoped delegation token for a sub-agent in a fleet.
 *
 * <p>{@code sessionToken} is only present immediately after {@code createDelegationToken()} —
 * hand it to the sub-agent and never store it. It is {@code null} on all subsequent reads.
 */
public record DelegationToken(
        String id,
        String parentBudgetId,
        String label,
        String status,
        String sessionTokenPrefix,
        List<DelegationTokenCap> caps,
        String revokedAt,
        String createdAt,
        /** Only present on createDelegationToken() response. Null on all subsequent reads. */
        String sessionToken
) {
    /** True when status is ACTIVE. */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** True when status is REVOKED. */
    public boolean isRevoked() {
        return "REVOKED".equals(status);
    }
}
