package com.figuard.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Validates that a spend request's intentContext is consistent with the budget's intentTags.
 *
 * Only applied on flat budgets (no allocations). Allocated budgets enforce intent through
 * claimedCategory — this validator is redundant and must NOT run on those paths.
 *
 * Logic:
 *   - Budget has no intentTags → always passes (permissive mode)
 *   - Budget has intentTags, request has no intentContext → DENY
 *   - Budget has intentTags, request has intentContext →
 *       any tag appears (case-insensitive) as a substring in intentContext → PASS
 *       no tag matches → DENY
 *
 * intentContext is NEVER used for enforcement on allocated budgets.
 * It is an audit/logging field there. This validator is the sole exception — flat
 * budgets use it as a soft intent gate because they have no claimedCategory to enforce.
 */
@Component
public class IntentScopeValidator {

    /**
     * @return true if the request is within scope, false if it should be denied
     */
    public boolean isInScope(String[] budgetIntentTags, String requestIntentContext) {
        // No tags → permissive, always pass
        if (budgetIntentTags == null || budgetIntentTags.length == 0) {
            return true;
        }

        // Tags exist but no context provided → deny
        if (requestIntentContext == null || requestIntentContext.isBlank()) {
            return false;
        }

        String lowerContext = requestIntentContext.toLowerCase();

        // Any tag appears as a substring in the context → pass
        return Arrays.stream(budgetIntentTags)
            .anyMatch(tag -> tag != null && lowerContext.contains(tag.toLowerCase()));
    }
}
