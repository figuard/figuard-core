package com.figuard.domain.enums;

public enum WebhookEventType {
    BUDGET_50_PCT,               // budget has reached 50% usage
    BUDGET_90_PCT,               // budget has reached 90% usage
    BUDGET_EXHAUSTED,            // budget available amount reached zero
    BUDGET_PAUSED,               // budget was paused (anomaly detection or manual) — stop spawning new sub-agents
    BUDGET_EXPIRING_SOON,        // budget will expire within 60 minutes — trigger extend or graceful shutdown
    ALLOCATION_EXHAUSTED,        // a category allocation hit zero — route remaining work to a different category
    SPEND_DENIED,                // an authorize call was denied
    SPEND_VOIDED,                // an authorized event was voided
    BUDGET_EXPIRED_UNUSED,       // budget expired without any authorize calls
    ANOMALY_DETECTED,            // requestedAmount exceeded the anomaly threshold
    BUDGET_RESUMED,              // paused budget was manually resumed via POST /budgets/{id}/resume
    SPEND_CONFIRMED,             // payment succeeded — event moved AUTHORIZED → CONFIRMED
    SPEND_PAYMENT_FAILED,        // payment failed — event moved AUTHORIZED → FAILED, reservation released
    LEDGER_INTEGRITY_VIOLATION,  // LedgerIntegrityService detected a balance invariant breach
    WEBHOOK_TEST,                // synthetic event fired by POST /webhooks/{id}/test
    DELEGATION_TOKEN_REVOKED,    // a delegation token was explicitly revoked via DELETE /delegation-tokens/{id}
    VELOCITY_LIMIT_EXCEEDED,     // a rolling-window rate limit was hit (first violation per window)
    RENEWAL_TOKEN_DELIVERY_FAILED,  // entitlement.renewed webhook failed 3+ sweep retries — operator must call rotate-tokens

    // Entitlement / subscription events
    ENTITLEMENT_STATE_CHANGED,      // NORMAL → APPROACHING or APPROACHING → LIMIT_REACHED
    ENTITLEMENT_LIMIT_REACHED,      // hard limit hit; BLOCK policy will deny further spend
    ENTITLEMENT_RENEWED,            // balance reset at renewal; new period started
    ENTITLEMENT_PAUSED,             // entitlement item manually paused by operator
    ENTITLEMENT_RESUMED             // paused entitlement item manually resumed
}
