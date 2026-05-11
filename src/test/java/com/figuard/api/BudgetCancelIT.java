package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.repository.AgentBudgetRepository;
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

/**
 * Integration tests for budget cancellation — the happy path, terminal state guards,
 * and behaviour after cancel (authorization rejected).
 */
class BudgetCancelIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;

    @Test
    void cancel_returns200_andStatusIsCancelled() throws Exception {
        String[] info = createBudget();
        String budgetId = info[0];

        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        var saved = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
        assertThat(saved.getCancelledAt()).isNotNull();
    }

    @Test
    void cancel_preventsFurtherAuthorization() throws Exception {
        String[] info = createBudget();
        String budgetId     = info[0];
        String sessionToken = info[1];

        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        // Authorize must now be denied — budget is cancelled
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_cancel_test",
                    "actionType", "PURCHASE",
                    "description", "post-cancel attempt",
                    "requestedQuantity", 10.00,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("BUDGET_CANCELLED"));
    }

    @Test
    void cancel_returns400_whenBudgetAlreadyCancelled() throws Exception {
        String[] info = createBudget();
        String budgetId = info[0];

        // First cancel
        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        // Second cancel — must fail
        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void cancel_returns404_forUnknownBudgetId() throws Exception {
        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void cancel_doesNotReleaseExistingReservations() throws Exception {
        String[] info = createBudget();
        String budgetId     = info[0];
        String sessionToken = info[1];

        // Authorize $100 — creates a reservation
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_cancel_test",
                    "actionType", "PURCHASE",
                    "description", "pending reservation",
                    "requestedQuantity", 100.00,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // Cancel the budget
        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        // Reservation must still be visible — cancel does not release it
        var budget = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        assertThat(budget.getQuantityReserved()).isEqualByComparingTo("100.00");
    }

    // -------------------------------------------------------------------------

    /** Returns [budgetId, sessionToken]. */
    private String[] createBudget() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "userId", "user_cancel_" + UUID.randomUUID(),
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
