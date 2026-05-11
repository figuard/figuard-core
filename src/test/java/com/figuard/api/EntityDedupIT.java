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

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EntityDedupIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    // -------------------------------------------------------------------------
    // Dedup disabled — default behaviour unchanged
    // -------------------------------------------------------------------------

    @Test
    void dedup_disabled_allowsSameEntityIdTwice() throws Exception {
        // Default: entityDedupEnabled=false — both authorizations go through
        Budget budget = createBudget(false);
        String entityId = "flight-AA123-" + UUID.randomUUID();

        authorize(budget, "50.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        authorize(budget, "50.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------
    // Dedup enabled — first authorize goes through, second is denied
    // -------------------------------------------------------------------------

    @Test
    void dedup_enabled_deniesSecondAuthorizationForSameEntityId() throws Exception {
        Budget budget = createBudget(true);
        String entityId = "hotel-HYT-" + UUID.randomUUID();

        // First request — authorized
        String firstResp = authorize(budget, "100.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        String firstEventId = objectMapper.readTree(firstResp).get("eventId").asText();

        // Second request — denied with ENTITY_ALREADY_AUTHORIZED and originalEventId pointer
        authorize(budget, "100.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("ENTITY_ALREADY_AUTHORIZED"))
            .andExpect(jsonPath("$.originalEventId").value(firstEventId));
    }

    @Test
    void dedup_enabled_allowsDifferentEntityIds() throws Exception {
        Budget budget = createBudget(true);

        authorize(budget, "50.00", "entity-A-" + UUID.randomUUID(), UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        authorize(budget, "50.00", "entity-B-" + UUID.randomUUID(), UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void dedup_enabled_allowsNullEntityId() throws Exception {
        // entityId=null requests are always allowed through — dedup check is skipped
        Budget budget = createBudget(true);

        authorizeNoEntity(budget, "50.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        authorizeNoEntity(budget, "50.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void dedup_enabled_idempotencyKeyTakesPrecedenceOverDedup() throws Exception {
        // If the idempotency key already exists, the original event is returned before
        // any dedup check — same key + same entityId → idempotent hit, not ENTITY_ALREADY_AUTHORIZED
        Budget budget = createBudget(true);
        String entityId = "flight-DL999-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        String firstResp = authorize(budget, "75.00", entityId, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        String firstEventId = objectMapper.readTree(firstResp).get("eventId").asText();

        // Same idempotency key — should be an idempotent replay, not a dedup denial
        String secondResp = authorize(budget, "75.00", entityId, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        String secondEventId = objectMapper.readTree(secondResp).get("eventId").asText();

        // Both responses point to the exact same event
        assert firstEventId.equals(secondEventId) : "idempotent replay must return same eventId";
    }

    @Test
    void dedup_enabled_afterVoidAllowsReathorization() throws Exception {
        // If the original AUTHORIZED event is voided (FAILED), the entityId slot is freed —
        // the partial unique index only covers AUTHORIZED and CONFIRMED rows.
        Budget budget = createBudget(true);
        String entityId = "taxi-UBER-" + UUID.randomUUID();

        // First authorize
        String firstResp = authorize(budget, "30.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(firstResp).get("eventId").asText();

        // Void / fail the first event
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "ride cancelled"}
                    """))
            .andExpect(status().isOk());

        // Re-authorize same entityId — should now succeed (AUTHORIZED row is gone)
        authorize(budget, "30.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void dedup_enabled_confirmedEventAlsoBlocksReauthorization() throws Exception {
        // A CONFIRMED event should also prevent re-authorization of the same entityId
        Budget budget = createBudget(true);
        String entityId = "hotel-MA-" + UUID.randomUUID();

        // First authorize
        String firstResp = authorize(budget, "200.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(firstResp).get("eventId").asText();

        // Confirm it
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"confirmedQuantity": 200.00}
                    """))
            .andExpect(status().isOk());

        // Try to re-authorize same entityId — CONFIRMED row blocks it
        authorize(budget, "200.00", entityId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("ENTITY_ALREADY_AUTHORIZED"))
            .andExpect(jsonPath("$.originalEventId").value(eventId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Budget createBudget(boolean entityDedupEnabled) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_dedup_test",
                    "intentContext", "travel spend",
                    "totalLimit", 1000.00,
                    "entityDedupEnabled", entityDedupEnabled,
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private org.springframework.test.web.servlet.ResultActions authorize(
            Budget budget, String amount, String entityId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/authorize")
            .header("X-Session-Token", budget.sessionToken())
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent_dedup_test",
                "actionType", "PURCHASE",
                "description", "dedup test",
                "requestedQuantity", Double.parseDouble(amount),
                "entityId", entityId,
                "idempotencyKey", idempotencyKey
            ))));
    }

    private org.springframework.test.web.servlet.ResultActions authorizeNoEntity(
            Budget budget, String amount, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/authorize")
            .header("X-Session-Token", budget.sessionToken())
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent_dedup_test",
                "actionType", "PURCHASE",
                "description", "dedup no-entity test",
                "requestedQuantity", Double.parseDouble(amount),
                "idempotencyKey", idempotencyKey
            ))));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
