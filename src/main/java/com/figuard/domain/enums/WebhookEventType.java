package com.figuard.domain.enums;

public enum WebhookEventType {
    BUDGET_50_PCT,           // budget has reached 50% usage
    BUDGET_90_PCT,           // budget has reached 90% usage
    BUDGET_EXHAUSTED,        // budget available amount reached zero
    SPEND_DENIED,            // an authorize call was denied
    SPEND_VOIDED,            // an authorized event was voided
    BUDGET_EXPIRED_UNUSED,   // budget expired without any authorize calls
    ANOMALY_DETECTED,              // requestedAmount exceeded the anomaly threshold; budget auto-paused
    BUDGET_RESUMED,                // paused budget was manually resumed via POST /budgets/{id}/resume
    SPEND_CONFIRMED,               // payment succeeded — event moved AUTHORIZED → CONFIRMED
    SPEND_PAYMENT_FAILED,          // payment failed — event moved AUTHORIZED → FAILED, reservation released
    LEDGER_INTEGRITY_VIOLATION,    // LedgerIntegrityService detected a balance invariant breach
    WEBHOOK_TEST                   // synthetic event fired by POST /webhooks/{id}/test
}
