package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CATEGORY_CONSTRAINED mode does not evaluate claimedItemType.
 * A request with a forbidden-looking item type must be authorized as long as
 * the category matches and funds are available.
 */
class CategoryConstrainedIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_succeeds_withAnyItemType_whenModeIsCategoryConstrained() throws Exception {
        // CATEGORY_CONSTRAINED mode — item type is never checked
        Budget budget = createCategoryConstrainedBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "category constrained test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "claimedCategory", "entertainment",
            "claimedItemType", "gift_card",    // would be blocked under STRICT — must pass here
            "idempotencyKey", UUID.randomUUID().toString()
        );

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void authorize_returnsDenied_whenCategoryDoesNotMatch_inCategoryConstrainedMode() throws Exception {
        Budget budget = createCategoryConstrainedBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "wrong category test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "claimedCategory", "flight",   // allocation only covers "entertainment"
            "idempotencyKey", UUID.randomUUID().toString()
        );

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("NO_MATCHING_ALLOCATION"));
    }

    // -------------------------------------------------------------------------

    private Budget createCategoryConstrainedBudget() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_constrained_test",
            "intentContext", "entertainment spend",
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", expiresAt(),
            "allocations", List.of(
                Map.of(
                    "category", "entertainment",
                    "allowedCategories", List.of("entertainment"),
                    "enforcementMode", "CATEGORY_CONSTRAINED",
                    // forbiddenItemTypes intentionally absent — mode doesn't check them anyway
                    "limit", 500.00
                )
            )
        );

        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
