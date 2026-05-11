package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnomalyDetectionIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired BudgetAnomalyBaselineRepository baselineRepository;

    // -------------------------------------------------------------------------

    @Test
    void anomalyDetection_pausesBudget_whenRequestExceedsThreshold() throws Exception {
        // Create a budget with anomaly detection enabled
        Budget budget = createAnomalyBudget(1000.00);

        // Seed the baseline with 5 confirmed events of $10 each so mean = $10
        seedBaseline(budget.id(), 5, new BigDecimal("10.00"));

        // Authorize $35 — exceeds mean($10) * multiplier(3.0) = $30 threshold
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("35.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("ANOMALY_DETECTED"));

        // Budget must be PAUSED after the anomaly denial
        AgentBudget paused = budgetRepository.findById(UUID.fromString(budget.id()))
            .orElseThrow();
        assertThat(paused.getStatus().name()).isEqualTo("PAUSED");
    }

    @Test
    void anomalyDetection_allowsRequest_belowThreshold() throws Exception {
        Budget budget = createAnomalyBudget(1000.00);
        seedBaseline(budget.id(), 5, new BigDecimal("10.00"));

        // Authorize $29.99 — just below mean($10) * 3.0 = $30
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("29.99")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void anomalyDetection_skipsCheck_whenSampleCountTooLow() throws Exception {
        Budget budget = createAnomalyBudget(1000.00);
        // Only 4 samples — anomalyMinSampleSize defaults to 5, so check is skipped
        seedBaseline(budget.id(), 4, new BigDecimal("10.00"));

        // Even though $500 >> $30 threshold, the check is skipped and request is AUTHORIZED
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("500.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void budgetResume_requiresOverrideReason() throws Exception {
        Budget budget = createAnomalyBudget(1000.00);
        seedBaseline(budget.id(), 5, new BigDecimal("10.00"));

        // Pause the budget via anomaly
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("35.00")))
            .andExpect(jsonPath("$.decision").value("DENIED"));

        // Resume without overrideReason must return 400
        mockMvc.perform(post("/api/v1/budgets/{id}/resume", budget.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void budgetResume_reactivatesBudget() throws Exception {
        Budget budget = createAnomalyBudget(1000.00);
        seedBaseline(budget.id(), 5, new BigDecimal("10.00"));

        // Trigger auto-pause via anomaly
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("35.00")))
            .andExpect(jsonPath("$.decision").value("DENIED"));

        // Resume the budget
        mockMvc.perform(post("/api/v1/budgets/{id}/resume", budget.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "overrideReason", "Reviewed and confirmed legitimate spend",
                    "overrideBy", "ops-team"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Verify the budget is now ACTIVE in the DB
        AgentBudget resumed = budgetRepository.findById(UUID.fromString(budget.id()))
            .orElseThrow();
        assertThat(resumed.getStatus().name()).isEqualTo("ACTIVE");

        // A below-threshold authorize should now succeed
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("5.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record Budget(String id, String sessionToken) {}

    private Budget createAnomalyBudget(double totalLimit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_anomaly_test",
                    "intentContext", "anomaly detection test",
                    "totalLimit", totalLimit,
                    "currency", "USD",
                    "anomalyDetectionEnabled", true,
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    /**
     * Directly seeds a baseline row so tests don't need to do N authorize+confirm cycles.
     * In production the baseline is built by AnomalyBaselineService after each CONFIRMED event.
     */
    private void seedBaseline(String budgetId, int sampleCount, BigDecimal mean) {
        AgentBudget budget = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        BudgetAnomalyBaseline baseline = new BudgetAnomalyBaseline();
        baseline.setBudget(budget);
        baseline.setTenant(tenant);
        baseline.setSampleCount(sampleCount);
        baseline.setMeanAmount(mean);
        baseline.setMinAmount(mean);
        baseline.setMaxAmount(mean);
        baselineRepository.save(baseline);
    }

    private String authorizeBody(String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_anomaly",
            "actionType", "PURCHASE",
            "description", "anomaly test purchase",
            "requestedQuantity", Double.parseDouble(amount),
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
