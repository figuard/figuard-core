package com.figuard.domain.enums;

public enum EnforcementMode {
    OPEN,                   // no category check — total limit only
    CATEGORY_CONSTRAINED,   // claimedCategory must be in allowedCategories (default)
    STRICT                  // claimedCategory must match AND claimedItemType not in forbiddenItemTypes
}
