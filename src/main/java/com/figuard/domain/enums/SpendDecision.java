package com.figuard.domain.enums;

// No APPROVED state — authorization creates AUTHORIZED with reserved funds.
// CONFIRMED = payment succeeded (funds permanently deducted).
// FAILED    = payment failed (reserved funds released).
public enum SpendDecision {
    AUTHORIZED,
    CONFIRMED,
    FAILED,
    DENIED,
    VOIDED
}
