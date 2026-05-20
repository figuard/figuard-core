package com.figuard.domain.enums;

public enum DenialCode {
    MISSING_SESSION_TOKEN,          // X-Session-Token header absent (HTTP 401)
    INVALID_SESSION_TOKEN,          // token not found or expired (HTTP 401)
    TENANT_MISMATCH,                // API key tenant does not match budget tenant (HTTP 403)
    CURRENCY_MISMATCH,              // requestedCurrency does not match budget.currency
    MISSING_CLAIMED_CATEGORY,       // budget has allocations but claimedCategory not provided
    NO_MATCHING_ALLOCATION,         // claimedCategory matches no allocation's allowedCategories
    FORBIDDEN_ITEM_TYPE,            // claimedItemType is in matched allocation's forbiddenItemTypes (STRICT)
    INSUFFICIENT_FUNDS,             // not enough budget remaining
    ALLOCATION_EXHAUSTED,           // matched allocation has no remaining funds
    BUDGET_EXHAUSTED,               // budget status is EXHAUSTED
    BUDGET_PAUSED,                  // budget status is PAUSED
    BUDGET_EXPIRED,                 // budget has passed expiresAt
    BUDGET_CANCELLED,               // budget was cancelled
    DUPLICATE_REQUEST,              // idempotency key already seen — returns original decision
    INVALID_PARENT_EVENT,           // parentEventId belongs to a different budget
    CAUSAL_CYCLE_DETECTED,          // parentEventId creates a cycle in the causal chain
    CAUSAL_CHAIN_TOO_DEEP,          // parentEventId chain exceeds 10 levels
    EXCEEDS_QUANTITY_LIMIT,         // requestedQuantity exceeds budget.maxTransactionQuantity ceiling
    INTENT_SCOPE_VIOLATION,         // flat budget has intentTags but request intentContext has no matching tag
    ANOMALY_DETECTED,               // requestedQuantity exceeds mean * multiplier threshold; budget auto-paused
    ENTITY_ALREADY_AUTHORIZED,      // entityId already has an AUTHORIZED or CONFIRMED event on this budget (dedup)
    DELEGATE_CAP_EXCEEDED,          // delegation token's per-category cap has no remaining capacity
    DELEGATION_TOKEN_REVOKED,       // the delegation token was explicitly revoked
    VELOCITY_LIMIT_EXCEEDED,        // rolling-window rate limit (per-minute, hourly amount, or per-day) reached
    ENTITLEMENT_LIMIT_REACHED,      // entitlement item balance exhausted; BLOCK overage policy
    ENTITLEMENT_NOT_FOUND,          // subscriptionId in session token has no matching entitlement item
    SUBSCRIPTION_PAUSED,            // subscription is paused — no spend allowed
    SUBSCRIPTION_CANCELLED          // subscription has been cancelled
}
