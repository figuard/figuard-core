package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the entitlement-backed authorization path.
 *
 * Flow:
 *   1. Create subscription + entitlement item (e.g. 1000 api_calls / month)
 *   2. Create budget linked to that entitlement item
 *   3. Authorize spend → verifies entitlement limit is enforced
 *   4. Confirm / fail / void → verifies consumed quantity is adjusted correctly
 */
class EntitlementEnforcementIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createSubscription(String externalSubscriberId) throws Exception {
        Map<String, Object> body = Map.of(
            "externalSubscriberId", externalSubscriberId,
            "name", "Test Subscription",
            "entitlementItems", List.of(Map.of(
                "name", "monthly_api_calls",
                "limitUnit", "api_calls",
                "limitQuantity", 1000,
                "renewalPeriod", "MONTHLY",
                "overagePolicy", "BLOCK",
                "warnAtPercentage", 80
            ))
        );
        String resp = mockMvc.perform(post("/api/v1/subscriptions")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return resp;
    }

    private String createBudgetWithEntitlement(String userId, UUID entitlementItemId) throws Exception {
        Map<String, Object> body = Map.of(
            "userId", userId,
            "intentContext", "entitlement integration test",
            "totalLimit", 999999,
            "unit", "api_calls",
            "expiresAt", expiresAt(),
            "entitlementItemId", entitlementItemId.toString()
        );
        return mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    private String authorize(String sessionToken, int quantity, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_entitlement_test",
                    "actionType", "API_CALL",
                    "description", "test api call",
                    "requestedQuantity", quantity,
                    "idempotencyKey", idempotencyKey))))
            .andReturn().getResponse().getContentAsString();
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void authorize_approved_when_within_entitlement_limit() throws Exception {
        String subResp = createSubscription("subscriber_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_happy", entitlementItemId);
        String sessionToken = objectMapper.readTree(budgetResp).get("tokens").get(0).get("sessionToken").asText();

        String authResp = authorize(sessionToken, 500, UUID.randomUUID().toString());
        JsonNode auth = objectMapper.readTree(authResp);

        assertThat(auth.get("decision").asText()).isEqualTo("AUTHORIZED");
    }

    @Test
    void authorize_denied_when_exceeds_entitlement_limit() throws Exception {
        String subResp = createSubscription("subscriber_block_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_block", entitlementItemId);
        JsonNode budget = objectMapper.readTree(budgetResp);
        String sessionToken = budget.get("tokens").get(0).get("sessionToken").asText();

        // Request more than the 1000 limit
        String authResp = authorize(sessionToken, 1500, UUID.randomUUID().toString());
        JsonNode auth = objectMapper.readTree(authResp);

        assertThat(auth.get("decision").asText()).isEqualTo("DENIED");
        assertThat(auth.get("denialReason").asText()).isEqualTo("ENTITLEMENT_LIMIT_REACHED");
    }

    @Test
    void authorize_denied_after_limit_exhausted_by_prior_spend() throws Exception {
        String subResp = createSubscription("subscriber_exhaust_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_exhaust", entitlementItemId);
        JsonNode budget = objectMapper.readTree(budgetResp);
        String sessionToken = budget.get("tokens").get(0).get("sessionToken").asText();

        // First authorization: consume 900 of 1000
        authorize(sessionToken, 900, UUID.randomUUID().toString());

        // Second authorization: try 200 more — should be denied (900 + 200 > 1000)
        String authResp = authorize(sessionToken, 200, UUID.randomUUID().toString());
        JsonNode auth = objectMapper.readTree(authResp);

        assertThat(auth.get("decision").asText()).isEqualTo("DENIED");
        assertThat(auth.get("denialReason").asText()).isEqualTo("ENTITLEMENT_LIMIT_REACHED");
    }

    @Test
    void fail_event_releases_entitlement_consumed() throws Exception {
        String subResp = createSubscription("subscriber_fail_release_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        String subscriptionId = sub.get("id").asText();
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_fail_release", entitlementItemId);
        JsonNode budget = objectMapper.readTree(budgetResp);
        String sessionToken = budget.get("tokens").get(0).get("sessionToken").asText();

        // Authorize 600
        String authResp = authorize(sessionToken, 600, UUID.randomUUID().toString());
        String eventId = objectMapper.readTree(authResp).get("eventId").asText();

        // Fail the event — 600 should be released
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "PAYMENT_DECLINED"))))
            .andExpect(status().isOk());

        // Now a second authorization of 800 should succeed (limit freed back to ~1000 available)
        String authResp2 = authorize(sessionToken, 800, UUID.randomUUID().toString());
        JsonNode auth2 = objectMapper.readTree(authResp2);
        assertThat(auth2.get("decision").asText()).isEqualTo("AUTHORIZED");
    }

    @Test
    void partial_confirm_adjusts_entitlement_consumed() throws Exception {
        String subResp = createSubscription("subscriber_partial_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        String subscriptionId = sub.get("id").asText();
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_partial", entitlementItemId);
        JsonNode budget = objectMapper.readTree(budgetResp);
        String sessionToken = budget.get("tokens").get(0).get("sessionToken").asText();

        // Authorize 500; confirm only 300 — 200 should return to entitlement pool
        String authResp = authorize(sessionToken, 500, UUID.randomUUID().toString());
        String eventId = objectMapper.readTree(authResp).get("eventId").asText();

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 300))))
            .andExpect(status().isOk());

        // 300 consumed, 700 remaining — a new auth of 650 should be approved
        String authResp2 = authorize(sessionToken, 650, UUID.randomUUID().toString());
        JsonNode auth2 = objectMapper.readTree(authResp2);
        assertThat(auth2.get("decision").asText()).isEqualTo("AUTHORIZED");
    }

    @Test
    void subscription_pause_blocks_authorization() throws Exception {
        String subResp = createSubscription("subscriber_pause_" + UUID.randomUUID());
        JsonNode sub = objectMapper.readTree(subResp);
        String subscriptionId = sub.get("id").asText();
        UUID entitlementItemId = UUID.fromString(sub.get("entitlementItems").get(0).get("id").asText());

        String budgetResp = createBudgetWithEntitlement("user_entitlement_pause", entitlementItemId);
        String sessionToken = objectMapper.readTree(budgetResp).get("tokens").get(0).get("sessionToken").asText();

        // Pause the subscription
        mockMvc.perform(post("/api/v1/subscriptions/{id}/pause", subscriptionId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));

        // Authorization should be blocked while paused — returns 402 PAYMENT_REQUIRED
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_pause_test",
                    "actionType", "API_CALL",
                    "description", "test api call",
                    "requestedQuantity", 100,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.error").value("SUBSCRIPTION_PAUSED"));
    }

}
