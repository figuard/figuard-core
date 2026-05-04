package com.figuard.service.model;

import com.figuard.domain.entity.BudgetAllocation;

/**
 * Result of CategoryMatchingService.findMatch().
 *
 * MissingCategory → claimedCategory was null/blank → reject with HTTP 400
 * NoMatch         → no allocation's allowedCategories contained claimedCategory → DENIED
 * Forbidden       → STRICT mode blocked the item type → DENIED
 * Match           → proceed with authorization against the matched allocation
 */
public sealed interface MatchResult
        permits MatchResult.MissingCategory,
                MatchResult.NoMatch,
                MatchResult.Forbidden,
                MatchResult.Match {

    record MissingCategory() implements MatchResult {}

    record NoMatch() implements MatchResult {}

    record Forbidden(BudgetAllocation allocation, String itemType) implements MatchResult {}

    record Match(BudgetAllocation allocation) implements MatchResult {}
}
