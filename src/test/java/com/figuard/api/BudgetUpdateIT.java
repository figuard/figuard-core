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
 * Integration tests for PATCH /api/v1/budgets/{id} — the updateBudget endpoint.
 */
class BudgetUpdateIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void updateBudget_canPauseActiveBudget() throws Exception {
        String budgetId = createBudget();

        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "PAUSED"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void updateBudget_canResumeFromPaused() throws Exception {
        String budgetId = createBudget();

        // Pause first
        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "PAUSED"))))
            .andExpect(status().isOk());

        // Resume
        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void updateBudget_canIncreaseTotalLimit() throws Exception {
        String budgetId = createBudget();

        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("totalLimit", 800.00))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalLimit").value(800.0));
    }

    @Test
    void updateBudget_rejects_settingExhaustedStatusViaApi() throws Exception {
        String budgetId = createBudget();

        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "EXHAUSTED"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void updateBudget_returns404_forUnknownId() throws Exception {
        mockMvc.perform(patch("/api/v1/budgets/{id}", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "PAUSED"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void pausedBudget_deniesAuthorization() throws Exception {
        String[] budgetInfo = createBudgetWithToken();
        String budgetId     = budgetInfo[0];
        String sessionToken = budgetInfo[1];

        // Pause
        mockMvc.perform(patch("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", "PAUSED"))))
            .andExpect(status().isOk());

        // Authorization must be denied while budget is paused
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_update_test",
                    "actionType", "PURCHASE",
                    "description", "auth while paused",
                    "requestedQuantity", 50.00,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("BUDGET_PAUSED"));
    }

    // -------------------------------------------------------------------------

    private String createBudget() throws Exception {
        return createBudgetWithToken()[0];
    }

    private String[] createBudgetWithToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "userId", "user_update_" + UUID.randomUUID(),
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));

        var result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{json.get("id").asText(), json.get("sessionToken").asText()};
    }
}
