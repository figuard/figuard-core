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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The INSUFFICIENT_FUNDS denial must explain WHY a budget is empty when the capacity is
 * held by unconfirmed authorizations rather than actually spent. This is the orchestrator
 * footgun: a parent reserves the whole budget, then a sub-agent is denied with a bare
 * "0 available" and no way to tell that an unconfirmed reservation — not real spend — is
 * the cause.
 */
class InsufficientFundsMessageIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void denial_explainsThatCapacityIsHeldByUnconfirmedReservations() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_orchestrator_footgun",
                    "intentContext", "orchestrator over-reservation",
                    "totalLimit", 100.00,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var createJson = objectMapper.readTree(createResp);
        String sessionToken = createJson.get("tokens").get(0).get("sessionToken").asText();

        // Orchestrator reserves the ENTIRE budget (the mistake), without confirming.
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "orchestrator",
                    "actionType", "task",
                    "description", "whole-budget reservation",
                    "requestedQuantity", 100.00,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // A sub-agent now tries to spend and is denied — nothing has actually been spent,
        // the orchestrator's unconfirmed reservation is holding everything.
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "sub-agent",
                    "actionType", "PURCHASE",
                    "description", "real spend",
                    "requestedQuantity", 10.00,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("INSUFFICIENT_FUNDS"))
            .andExpect(jsonPath("$.denialMessage", containsString("reserved by unconfirmed authorizations")))
            .andExpect(jsonPath("$.denialMessage", containsString("confirm or void them")));
    }

    @Test
    void denial_doesNotMentionReservations_whenBudgetIsGenuinelySpent() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_genuinely_spent",
                    "intentContext", "genuinely spent budget",
                    "totalLimit", 50.00,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var createJson = objectMapper.readTree(createResp);
        String sessionToken = createJson.get("tokens").get(0).get("sessionToken").asText();

        // Authorize and CONFIRM the full budget — now it is genuinely spent, not reserved.
        String authResp = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent",
                    "actionType", "PURCHASE",
                    "description", "spend it all",
                    "requestedQuantity", 50.00,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(authResp).get("eventId").asText();

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 50.00))))
            .andExpect(status().isOk());

        // Next request is denied — but this time the cause is real spend, so the message
        // must NOT blame unconfirmed reservations.
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent",
                    "actionType", "PURCHASE",
                    "description", "one more",
                    "requestedQuantity", 5.00,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("INSUFFICIENT_FUNDS"))
            .andExpect(jsonPath("$.denialMessage", not(containsString("reserved by unconfirmed"))));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
