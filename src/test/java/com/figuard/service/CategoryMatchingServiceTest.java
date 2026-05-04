package com.figuard.service;

import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.enums.EnforcementMode;
import com.figuard.service.model.MatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryMatchingServiceTest {

    private CategoryMatchingService service;

    private BudgetAllocation flightAllocation;
    private BudgetAllocation hotelAllocation;

    @BeforeEach
    void setUp() {
        service = new CategoryMatchingService();

        flightAllocation = allocation("flight", new String[]{"flight"}, EnforcementMode.CATEGORY_CONSTRAINED, null);
        hotelAllocation  = allocation("hotel",  new String[]{"hotel"},  EnforcementMode.CATEGORY_CONSTRAINED, null);
    }

    @Test
    void claimedCategory_flight_matchesFlightAllocation() {
        MatchResult result = service.findMatch(
            List.of(flightAllocation, hotelAllocation), "flight", null);

        assertThat(result).isInstanceOf(MatchResult.Match.class);
        assertThat(((MatchResult.Match) result).allocation()).isSameAs(flightAllocation);
    }

    @Test
    void claimedCategory_hotel_matchesHotelAllocation() {
        MatchResult result = service.findMatch(
            List.of(flightAllocation, hotelAllocation), "hotel", null);

        assertThat(result).isInstanceOf(MatchResult.Match.class);
        assertThat(((MatchResult.Match) result).allocation()).isSameAs(hotelAllocation);
    }

    @Test
    void claimedCategory_car_rental_noMatch() {
        MatchResult result = service.findMatch(
            List.of(flightAllocation, hotelAllocation), "car_rental", null);

        assertThat(result).isInstanceOf(MatchResult.NoMatch.class);
    }

    @Test
    void claimedCategory_null_missingCategory() {
        MatchResult result = service.findMatch(
            List.of(flightAllocation, hotelAllocation), null, null);

        assertThat(result).isInstanceOf(MatchResult.MissingCategory.class);
    }

    @Test
    void claimedCategory_blank_missingCategory() {
        MatchResult result = service.findMatch(
            List.of(flightAllocation, hotelAllocation), "  ", null);

        assertThat(result).isInstanceOf(MatchResult.MissingCategory.class);
    }

    @Test
    void strictMode_forbiddenItemType_returnsForbidden() {
        BudgetAllocation strictFlight = allocation("flight", new String[]{"flight"},
            EnforcementMode.STRICT, new String[]{"gift_card"});

        MatchResult result = service.findMatch(
            List.of(strictFlight), "flight", "gift_card");

        assertThat(result).isInstanceOf(MatchResult.Forbidden.class);
        MatchResult.Forbidden forbidden = (MatchResult.Forbidden) result;
        assertThat(forbidden.allocation()).isSameAs(strictFlight);
        assertThat(forbidden.itemType()).isEqualTo("gift_card");
    }

    @Test
    void strictMode_allowedItemType_returnsMatch() {
        BudgetAllocation strictFlight = allocation("flight", new String[]{"flight"},
            EnforcementMode.STRICT, new String[]{"gift_card"});

        MatchResult result = service.findMatch(
            List.of(strictFlight), "flight", "airline_ticket");

        assertThat(result).isInstanceOf(MatchResult.Match.class);
    }

    @Test
    void categoryConstrained_forbiddenItemTypeIgnored_returnsMatch() {
        // STRICT not active — forbiddenItemTypes list has no effect
        BudgetAllocation ccFlight = allocation("flight", new String[]{"flight"},
            EnforcementMode.CATEGORY_CONSTRAINED, new String[]{"gift_card"});

        MatchResult result = service.findMatch(
            List.of(ccFlight), "flight", "gift_card");

        assertThat(result).isInstanceOf(MatchResult.Match.class);
    }

    @Test
    void strictMode_nullItemType_returnsMatch() {
        // null itemType is never blocked, even in STRICT mode
        BudgetAllocation strictFlight = allocation("flight", new String[]{"flight"},
            EnforcementMode.STRICT, new String[]{"gift_card"});

        MatchResult result = service.findMatch(
            List.of(strictFlight), "flight", null);

        assertThat(result).isInstanceOf(MatchResult.Match.class);
    }

    // -------------------------------------------------------------------------

    private static BudgetAllocation allocation(String category,
                                               String[] allowedCategories,
                                               EnforcementMode mode,
                                               String[] forbiddenItemTypes) {
        BudgetAllocation a = new BudgetAllocation();
        a.setCategory(category);
        a.setAllowedCategories(allowedCategories);
        a.setEnforcementMode(mode);
        a.setForbiddenItemTypes(forbiddenItemTypes);
        return a;
    }
}
