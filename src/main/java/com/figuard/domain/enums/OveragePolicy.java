package com.figuard.domain.enums;

public enum OveragePolicy {
    BLOCK,       // hard stop at limit — authorization denied
    WARN_ONLY    // webhook fires at limit but agent continues
    // ALLOW_WITH_OVERAGE is v2 (requires credit pack infrastructure)
}
