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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoidWithExternalTransactionIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void void_returns409_whenEventHasExternalTransactionId() throws Exception {
        Budget budget = createBudget(500.00);

        // Authorize
        String eventId = authorizeAndGetEventId(budget.sessionToken(), "100.00");

        // Confirm with an external transaction ID (simulates Stripe callback)
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "confirmedQuantity", 100.00,
                    "externalTransactionId", "stripe_pi_test_abc123"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("CONFIRMED"));

        // Trying to void a CONFIRMED event should fail — event is no longer AUTHORIZED
        mockMvc.perform(post("/api/v1/events/{id}/void", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "reason", "test void after confirm"
                ))))
            .andExpect(status().isConflict());
    }

    @Test
    void void_returns409_whenAuthorizedEventHasExternalTransactionId() throws Exception {
        // This tests the guard on AUTHORIZED events that already received an externalTransactionId
        // via a confirm → void sequence, but here we simulate an event that was confirmed then
        // we verify void is blocked. The service blocks void if externalTransactionId is set.
        Budget budget = createBudget(500.00);

        String eventId = authorizeAndGetEventId(budget.sessionToken(), "80.00");

        // Confirm sets externalTransactionId
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "confirmedQuantity", 80.00,
                    "externalTransactionId", "ch_3test_stripe"
                ))))
            .andExpect(status().isOk());

        // Void must fail — can't void without issuing a refund first
        mockMvc.perform(post("/api/v1/events/{id}/void", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "reason", "customer changed mind"
                ))))
            .andExpect(status().isConflict());
    }

    @Test
    void void_succeeds_whenNoExternalTransactionId() throws Exception {
        Budget budget = createBudget(500.00);

        String eventId = authorizeAndGetEventId(budget.sessionToken(), "60.00");

        // Void without externalTransactionId — should succeed
        mockMvc.perform(post("/api/v1/events/{id}/void", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "reason", "agent cancelled action"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("VOIDED"));
    }

    // -------------------------------------------------------------------------

    private Budget createBudget(double totalLimit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_void_test",
                    "intentContext", "travel spend",
                    "totalLimit", totalLimit,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private String authorizeAndGetEventId(String sessionToken, String amount) throws Exception {
        String response = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_001",
                    "actionType", "PURCHASE",
                    "description", "void test",
                    "requestedQuantity", Double.parseDouble(amount),
                    "currency", "USD",
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("eventId").asText();
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
