package com.figuard.domain.enums;

public enum EntitlementState {
    NORMAL,        // below approach threshold
    APPROACHING,   // crossed warn_at_percentage (default 80%)
    LIMIT_REACHED  // at or above limit; BLOCK policy denies further spend
    // IN_OVERAGE and OVERAGE_EXHAUSTED are v2 (requires credit packs)
}
