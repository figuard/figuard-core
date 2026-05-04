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

class MissingClaimedCategoryIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_denies_whenAllocatedBudgetHasNoClaimedCategory() throws Exception {
        // Budget has allocations — agent must provide claimedCategory.
        // Returns structured DENIED (not 400) so LLMs get a parseable decision with audit trail.
        Budget budget = createAllocatedBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "missing category test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
            // claimedCategory intentionally omitted
        );

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("MISSING_CLAIMED_CATEGORY"));
    }

    @Test
    void authorize_succeeds_whenClaimedCategoryMatchesAllocation() throws Exception {
        Budget budget = createAllocatedBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "valid category test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "claimedCategory", "flight",
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

    // -------------------------------------------------------------------------

    private Budget createAllocatedBudget() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_category_test",
            "intentContext", "travel spend",
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", expiresAt(),
            "allocations", List.of(
                Map.of(
                    "category", "flight",
                    "allowedCategories", List.of("flight"),
                    "limit", 300.00
                ),
                Map.of(
                    "category", "hotel",
                    "allowedCategories", List.of("hotel"),
                    "limit", 200.00
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
