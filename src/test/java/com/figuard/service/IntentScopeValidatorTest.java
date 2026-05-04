package com.figuard.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentScopeValidatorTest {

    private final IntentScopeValidator validator = new IntentScopeValidator();

    @Test
    void passes_whenBudgetHasNoIntentTags() {
        assertThat(validator.isInScope(null, null)).isTrue();
        assertThat(validator.isInScope(new String[0], "some context")).isTrue();
    }

    @Test
    void denies_whenTagsPresent_andNoContext() {
        assertThat(validator.isInScope(new String[]{"travel", "flight"}, null)).isFalse();
        assertThat(validator.isInScope(new String[]{"travel", "flight"}, "")).isFalse();
        assertThat(validator.isInScope(new String[]{"travel", "flight"}, "   ")).isFalse();
    }

    @Test
    void passes_whenTagAppearsInContext() {
        assertThat(validator.isInScope(
            new String[]{"travel", "flight"},
            "purchase flight ticket to NYC")).isTrue();
    }

    @Test
    void passes_whenAnyOneTagMatches() {
        // only "hotel" overlaps — "flight" does not — still passes
        assertThat(validator.isInScope(
            new String[]{"flight", "hotel"},
            "book hotel room for conference")).isTrue();
    }

    @Test
    void denies_whenNoTagOverlap() {
        assertThat(validator.isInScope(
            new String[]{"travel", "flight"},
            "book hotel room")).isFalse();
    }

    @Test
    void matchIsCaseInsensitive() {
        assertThat(validator.isInScope(
            new String[]{"FLIGHT"},
            "purchase flight ticket")).isTrue();

        assertThat(validator.isInScope(
            new String[]{"flight"},
            "Purchase FLIGHT Ticket")).isTrue();
    }

    @Test
    void denies_whenContextUnrelated_multipleTagsNoneMatch() {
        assertThat(validator.isInScope(
            new String[]{"refund", "cash_back", "reversal"},
            "process vendor invoice payment")).isFalse();
    }

    @Test
    void passes_whenTagIsSubstringOfContextWord() {
        // "travel" appears inside "corporate_travel_booking"
        assertThat(validator.isInScope(
            new String[]{"travel"},
            "corporate_travel_booking")).isTrue();
    }
}
