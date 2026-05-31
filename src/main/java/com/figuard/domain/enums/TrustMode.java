package com.figuard.domain.enums;

/**
 * Controls whether FiGuard blocks denials or observes silently.
 *
 * <p>SHADOW — All enforcement checks run (budget exhaustion, velocity, allocation caps, anomaly
 * detection) but the authorize call always returns AUTHORIZED. The response includes
 * {@code shadow=true} and {@code wouldHaveBeen} / {@code wouldHaveBeenReason} so the caller
 * can see what would have happened. No SpendEvent is written.
 *
 * <p>Use this when onboarding a new agent type or a new spend category. Run in SHADOW for
 * a week, observe what would have been denied, tune budget sizes and velocity limits, then
 * flip to FULL_ENFORCEMENT via {@code PATCH /budgets/{id}}.
 *
 * <p>FULL_ENFORCEMENT — Default. Enforcement checks run and denials are returned.
 * SpendEvents are written to the ledger.
 */
public enum TrustMode {
    SHADOW,
    FULL_ENFORCEMENT
}
