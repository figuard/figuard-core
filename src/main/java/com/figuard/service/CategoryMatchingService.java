package com.figuard.service;

import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.service.model.MatchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryMatchingService {

    /**
     * Finds the matching allocation for a spend authorization request.
     * Uses direct string equality — no fuzzy matching, no case folding, no keyword inference.
     *
     * @param allocations    active allocations on the budget
     * @param claimedCategory the category declared by the agent
     * @param claimedItemType the item type declared by the agent (optional, evaluated in STRICT mode)
     * @return MatchResult describing the outcome
     */
    public MatchResult findMatch(List<BudgetAllocation> allocations,
                                 String claimedCategory,
                                 String claimedItemType) {
        if (claimedCategory == null || claimedCategory.isBlank()) {
            return new MatchResult.MissingCategory();
        }

        for (BudgetAllocation allocation : allocations) {
            if (allocation.matchesCategory(claimedCategory)) {
                if (allocation.isItemTypeForbidden(claimedItemType)) {
                    return new MatchResult.Forbidden(allocation, claimedItemType);
                }
                return new MatchResult.Match(allocation);
            }
        }

        return new MatchResult.NoMatch();
    }
}
