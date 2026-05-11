package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdempotencyIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_returnsSameDecision_onDuplicateIdempotencyKey() throws Exception {
        Budget budget = createBudget(500.00);
        String idempotencyKey = UUID.randomUUID().toString();

        // First call — AUTHORIZED
        String first = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("100.00", idempotencyKey)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();

        String firstEventId = objectMapper.readTree(first).get("eventId").asText();

        // Second call — same key, must return same event, no new reservation
        String second = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("100.00", idempotencyKey)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();

        String secondEventId = objectMapper.readTree(second).get("eventId").asText();

        assertThat(secondEventId).isEqualTo(firstEventId);

        // Budget was debited exactly once — only one event in the ledger
        mockMvc.perform(get("/api/v1/budgets/{id}/ledger", budget.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void authorize_returns400_whenIdempotencyKeyMissing() throws Exception {
        Budget budget = createBudget(500.00);

        Map<String, Object> body = Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "test purchase",
            "requestedQuantity", 50.00,
            "currency", "USD"
            // idempotencyKey intentionally omitted
        );

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.idempotencyKey").isNotEmpty());
    }

    // -------------------------------------------------------------------------

    private Budget createBudget(double totalLimit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_idempotency_test",
                    "intentContext", "travel spend",
                    "totalLimit", totalLimit,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private String authorizeBody(String amount, String idempotencyKey) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "test purchase",
            "requestedQuantity", Double.parseDouble(amount),
            "currency", "USD",
            "idempotencyKey", idempotencyKey
        ));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
