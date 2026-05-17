package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionTokenRotationIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;

    /**
     * New token returned by rotate-token must work immediately for authorization.
     */
    @Test
    void rotateToken_newTokenWorksImmediately() throws Exception {
        String[] info = createFlatBudget();
        UUID budgetId = UUID.fromString(info[0]);
        // Discard old token — we only care about the new one after rotation

        String newToken = rotateToken(budgetId);

        String decision = authorize(newToken, new BigDecimal("10.00"));
        assertThat(decision).isEqualTo("AUTHORIZED");
    }

    /**
     * Old token must still work within the grace period immediately after rotation.
     * This is the core safety guarantee — in-flight agents are not dropped.
     */
    @Test
    void rotateToken_oldTokenStillWorksWithinGracePeriod() throws Exception {
        String[] info = createFlatBudget();
        UUID budgetId = UUID.fromString(info[0]);
        String oldToken = info[1];

        rotateToken(budgetId); // rotates — old token now in previousSessionTokenHash

        // Old token should still work — grace period has not expired
        String decision = authorize(oldToken, new BigDecimal("10.00"));
        assertThat(decision).isEqualTo("AUTHORIZED");
    }

    /**
     * Old token must be rejected after the grace period expires.
     * We simulate expiry by setting tokenRotationExpiresAt to the past directly on the entity.
     */
    @Test
    void rotateToken_oldTokenRejectedAfterGracePeriod() throws Exception {
        String[] info = createFlatBudget();
        UUID budgetId = UUID.fromString(info[0]);
        String oldToken = info[1];

        rotateToken(budgetId);

        // Simulate grace period expiry by backdating the rotation timestamp
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setTokenRotationExpiresAt(OffsetDateTime.now().minusSeconds(10));
        budgetRepository.save(budget);

        // Old token should now be rejected — grace window has closed
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", oldToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(new BigDecimal("10.00"))))
            .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns [budgetId, sessionToken] */
    private String[] createFlatBudget() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_rotation_test_" + UUID.randomUUID(),
            "totalLimit", 200.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{ node.get("id").asText(), node.get("tokens").get(0).get("sessionToken").asText() };
    }

    private String rotateToken(UUID budgetId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/budgets/{id}/rotate-token", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("tokens").get(0).get("sessionToken").asText();
    }

    private String authorize(String sessionToken, BigDecimal amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(amount)))
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("decision").asText();
    }

    private String authorizeBody(BigDecimal amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_rotation_test",
            "actionType", "PURCHASE",
            "description", "Rotation test purchase",
            "requestedQuantity", amount,
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }
}
