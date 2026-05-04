package com.figuard.domain.entity;

import com.figuard.domain.enums.AllocationStatus;
import com.figuard.domain.enums.EnforcementMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAllocationTest {

    private BudgetAllocation allocation(String[] allowedCategories, String[] forbiddenItemTypes,
                                        EnforcementMode mode) {
        BudgetAllocation a = new BudgetAllocation();
        a.setCategory("flight");
        a.setAllowedCategories(allowedCategories);
        a.setForbiddenItemTypes(forbiddenItemTypes);
        a.setEnforcementMode(mode);
        a.setTotalLimit(new BigDecimal("500.00"));
        a.setAmountSpent(BigDecimal.ZERO);
        a.setAmountReserved(BigDecimal.ZERO);
        a.setStatus(AllocationStatus.ACTIVE);
        return a;
    }

    // --- matchesCategory ---

    @Test
    void matchesCategory_returnsTrue_whenCategoryInAllowedCategories() {
        BudgetAllocation a = allocation(new String[]{"flight", "airfare"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        assertThat(a.matchesCategory("flight")).isTrue();
        assertThat(a.matchesCategory("airfare")).isTrue();
    }

    @Test
    void matchesCategory_returnsFalse_whenCategoryNotInAllowedCategories() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        assertThat(a.matchesCategory("hotel")).isFalse();
    }

    @Test
    void matchesCategory_returnsFalse_whenClaimedCategoryIsNull() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        assertThat(a.matchesCategory(null)).isFalse();
    }

    @Test
    void matchesCategory_returnsFalse_whenClaimedCategoryIsBlank() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        assertThat(a.matchesCategory("   ")).isFalse();
    }

    // --- isItemTypeForbidden ---

    @Test
    void isItemTypeForbidden_returnsTrue_whenStrictModeAndTypeInList() {
        BudgetAllocation a = allocation(
            new String[]{"flight"},
            new String[]{"gift_card", "store_credit"},
            EnforcementMode.STRICT
        );
        assertThat(a.isItemTypeForbidden("gift_card")).isTrue();
    }

    @Test
    void isItemTypeForbidden_returnsFalse_whenNotStrictMode() {
        BudgetAllocation a = allocation(
            new String[]{"flight"},
            new String[]{"gift_card"},
            EnforcementMode.CATEGORY_CONSTRAINED    // not STRICT
        );
        assertThat(a.isItemTypeForbidden("gift_card")).isFalse();
    }

    @Test
    void isItemTypeForbidden_returnsFalse_whenItemTypeIsNull() {
        BudgetAllocation a = allocation(
            new String[]{"flight"},
            new String[]{"gift_card"},
            EnforcementMode.STRICT
        );
        assertThat(a.isItemTypeForbidden(null)).isFalse();
    }

    // --- canAccommodate ---

    @Test
    void canAccommodate_returnsFalse_whenExhausted() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        a.setStatus(AllocationStatus.EXHAUSTED);
        assertThat(a.canAccommodate(new BigDecimal("10.00"))).isFalse();
    }

    @Test
    void canAccommodate_returnsFalse_whenInsufficientFunds() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        a.setAmountSpent(new BigDecimal("490.00"));
        // Available = $10.00; requesting $20.00 should fail
        assertThat(a.canAccommodate(new BigDecimal("20.00"))).isFalse();
    }

    @Test
    void canAccommodate_returnsTrue_whenActiveAndEnoughFunds() {
        BudgetAllocation a = allocation(new String[]{"flight"}, null, EnforcementMode.CATEGORY_CONSTRAINED);
        assertThat(a.canAccommodate(new BigDecimal("100.00"))).isTrue();
    }
}
