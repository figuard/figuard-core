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

class ForbiddenItemTypeIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_returnsDenied_whenClaimedItemTypeIsForbiddenInStrictMode() throws Exception {
        // STRICT allocation: "gift_card" is explicitly forbidden
        Budget budget = createStrictBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "forbidden item test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "claimedCategory", "entertainment",
            "claimedItemType", "gift_card",    // explicitly blocked
            "idempotencyKey", UUID.randomUUID().toString()
        );

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("FORBIDDEN_ITEM_TYPE"));
    }

    @Test
    void authorize_succeeds_whenClaimedItemTypeIsNotForbidden() throws Exception {
        Budget budget = createStrictBudget();

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "allowed item test",
            "requestedAmount", 50.00,
            "currency", "USD",
            "claimedCategory", "entertainment",
            "claimedItemType", "concert_ticket",   // not in forbidden list
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

    private Budget createStrictBudget() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_strict_test",
            "intentContext", "entertainment spend",
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", expiresAt(),
            "allocations", List.of(
                Map.of(
                    "category", "entertainment",
                    "allowedCategories", List.of("entertainment"),
                    "enforcementMode", "STRICT",
                    "forbiddenItemTypes", List.of("gift_card", "crypto"),
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
