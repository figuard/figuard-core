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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Validates the 60-second expiry grace window.
 *
 * Agents may send an authorize request fractionally after expiresAt due to network
 * latency or clock skew. The service adds a 60-second grace buffer so these requests
 * are not spuriously rejected. Any request arriving more than 60 seconds after
 * expiresAt is denied with BUDGET_EXPIRED.
 */
class ClockSkewExpiryIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_succeeds_whenRequestArrivesWithinGraceWindowAfterExpiry() throws Exception {
        // Budget expires in 2 seconds. We sleep 3 seconds to push past expiresAt,
        // then authorize — should still succeed because we are within the 60s grace buffer.
        Budget budget = createBudgetExpiringInSeconds(2);

        Thread.sleep(3_000); // now 1 second past expiresAt, still within 60s grace

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("50.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void authorize_succeeds_beforeExpiry() throws Exception {
        // Normal case: budget still active — baseline to confirm expiry logic is not over-eager
        Budget budget = createBudgetExpiringInSeconds(60);

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("50.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------

    private Budget createBudgetExpiringInSeconds(int seconds) throws Exception {
        String expiresAt = OffsetDateTime.now()
            .plusSeconds(seconds)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_expiry_test",
                    "intentContext", "travel spend",
                    "totalLimit", 500.00,
                    "currency", "USD",
                    "expiresAt", expiresAt))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private String authorizeBody(String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "clock skew test",
            "requestedAmount", Double.parseDouble(amount),
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }
}
