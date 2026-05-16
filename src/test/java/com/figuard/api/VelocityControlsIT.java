package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for rolling-window velocity controls on budgets.
 *
 * Key behaviours verified:
 *  - First N requests authorized, (N+1)th denied with VELOCITY_LIMIT_EXCEEDED
 *  - Subsequent violations silently return the same event ID as the first denial
 *  - PATCH /budgets/{id} updates velocity limits and they take effect immediately
 *  - BudgetResponse includes all three velocity fields
 */
class VelocityControlsIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // per-minute count limit
    // -------------------------------------------------------------------------

    @Test
    void velocityPerMinute_firstTwoAuthorized_thirdDenied() throws Exception {
        Budget b = createBudget(500.00, 2, null, null);

        // First two requests must be AUTHORIZED
        authorize(b.sessionToken(), "10.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        authorize(b.sessionToken(), "10.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // Third request must be denied
        String thirdResponse = authorize(b.sessionToken(), "10.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("VELOCITY_LIMIT_EXCEEDED"))
            .andReturn().getResponse().getContentAsString();

        String firstDenialEventId = objectMapper.readTree(thirdResponse).get("eventId").asText();
        assertThat(firstDenialEventId).isNotBlank();

        // Fourth request: silently denied, same event ID as the third (dedup)
        String fourthResponse = authorize(b.sessionToken(), "10.00", UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("VELOCITY_LIMIT_EXCEEDED"))
            .andReturn().getResponse().getContentAsString();

        String dedupEventId = objectMapper.readTree(fourthResponse).get("eventId").asText();
        assertThat(dedupEventId).isEqualTo(firstDenialEventId);
    }

    // -------------------------------------------------------------------------
    // dry-run: velocity check runs but nothing is written
    // -------------------------------------------------------------------------

    @Test
    void velocityPerMinute_dryRun_returnsPhantomDenial_noWrite() throws Exception {
        Budget b = createBudget(500.00, 1, null, null);

        // Exhaust the limit with a real authorize
        authorize(b.sessionToken(), "10.00", UUID.randomUUID().toString())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // Dry-run should see the limit hit and return DENIED without writing
        String dryRunBody = objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_velocity_test",
            "actionType", "PURCHASE",
            "description", "dry run velocity test",
            "requestedQuantity", 10.00,
            "currency", "USD",
            "dryRun", true,
            "idempotencyKey", UUID.randomUUID().toString()
        ));

        String dryResponse = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", b.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(dryRunBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("VELOCITY_LIMIT_EXCEEDED"))
            .andReturn().getResponse().getContentAsString();

        // Dry-run event ID should be null (phantom, never saved)
        assertThat(objectMapper.readTree(dryResponse).get("eventId").isNull()).isTrue();
    }

    // -------------------------------------------------------------------------
    // PATCH updates velocity limits
    // -------------------------------------------------------------------------

    @Test
    void patchBudget_updatesVelocityLimits() throws Exception {
        Budget b = createBudget(500.00, null, null, null);

        // Update all three velocity limits
        mockMvc.perform(patch("/api/v1/budgets/{id}", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "velocityMaxPerMinute", 5,
                    "velocityMaxAmountPerHour", 200.00,
                    "velocityMaxPerDay", 100
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.velocityMaxPerMinute").value(5))
            .andExpect(jsonPath("$.velocityMaxAmountPerHour").value(200.0))
            .andExpect(jsonPath("$.velocityMaxPerDay").value(100));
    }

    // -------------------------------------------------------------------------
    // BudgetResponse includes velocity fields on GET
    // -------------------------------------------------------------------------

    @Test
    void getBudget_includesVelocityFieldsInResponse() throws Exception {
        Budget b = createBudget(500.00, 3, new java.math.BigDecimal("150.00"), 50);

        mockMvc.perform(get("/api/v1/budgets/{id}", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.velocityMaxPerMinute").value(3))
            .andExpect(jsonPath("$.velocityMaxAmountPerHour").value(150.0))
            .andExpect(jsonPath("$.velocityMaxPerDay").value(50));
    }

    // -------------------------------------------------------------------------
    // Budget created without velocity limits — no velocity fields in response
    // -------------------------------------------------------------------------

    @Test
    void getBudget_noVelocityFields_whenNotConfigured() throws Exception {
        Budget b = createBudget(500.00, null, null, null);

        String response = mockMvc.perform(get("/api/v1/budgets/{id}", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        // @JsonInclude(NON_NULL) means absent fields should not appear
        assertThat(json.has("velocityMaxPerMinute")).isFalse();
        assertThat(json.has("velocityMaxAmountPerHour")).isFalse();
        assertThat(json.has("velocityMaxPerDay")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record Budget(String id, String sessionToken) {}

    /** Creates a budget with optional velocity controls. */
    private Budget createBudget(double totalLimit,
                                 Integer maxPerMinute,
                                 java.math.BigDecimal maxAmountPerHour,
                                 Integer maxPerDay) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("userId", "user_velocity_" + UUID.randomUUID());
        body.put("totalLimit", totalLimit);
        body.put("currency", "USD");
        body.put("expiresAt", expiresAt());
        if (maxPerMinute != null)     body.put("velocityMaxPerMinute", maxPerMinute);
        if (maxAmountPerHour != null) body.put("velocityMaxAmountPerHour", maxAmountPerHour);
        if (maxPerDay != null)        body.put("velocityMaxPerDay", maxPerDay);

        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        // tokens[0].sessionToken is the raw session token
        String sessionToken = json.get("tokens").get(0).get("sessionToken").asText();
        return new Budget(json.get("id").asText(), sessionToken);
    }

    private org.springframework.test.web.servlet.ResultActions authorize(
            String sessionToken, String amount, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/authorize")
            .header("X-Session-Token", sessionToken)
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent_velocity_test",
                "actionType", "PURCHASE",
                "description", "velocity test",
                "requestedQuantity", Double.parseDouble(amount),
                "currency", "USD",
                "idempotencyKey", idempotencyKey
            ))));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
