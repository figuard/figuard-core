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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetFundIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // CREDIT
    // -------------------------------------------------------------------------

    @Test
    void credit_increases_totalLimit() throws Exception {
        BudgetWithToken b = createBudget(500.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("CREDIT", 200.00)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operation").value("CREDIT"))
            .andExpect(jsonPath("$.previousTotalLimit").value(500.00))
            .andExpect(jsonPath("$.totalLimit").value(700.00))
            .andExpect(jsonPath("$.availableQuantity").value(700.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void credit_with_reason_is_returned() throws Exception {
        BudgetWithToken b = createBudget(100.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "operation", "CREDIT",
                    "amount", 50.00,
                    "reason", "monthly top-up"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reason").value("monthly top-up"));
    }

    // -------------------------------------------------------------------------
    // DEBIT
    // -------------------------------------------------------------------------

    @Test
    void debit_decreases_totalLimit() throws Exception {
        BudgetWithToken b = createBudget(500.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("DEBIT", 100.00)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previousTotalLimit").value(500.00))
            .andExpect(jsonPath("$.totalLimit").value(400.00))
            .andExpect(jsonPath("$.availableQuantity").value(400.00));
    }

    @Test
    void debit_returns400_when_result_would_be_below_quantitySpent() throws Exception {
        BudgetWithToken b = createBudget(500.00);
        String eventId = authorize(b.sessionToken(), 300.00);
        confirm(eventId, 300.00);

        // DEBIT 300 → totalLimit = 200, below quantitySpent = 300
        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("DEBIT", 300.00)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("quantitySpent")));
    }

    // -------------------------------------------------------------------------
    // RESET
    // -------------------------------------------------------------------------

    @Test
    void reset_sets_totalLimit_exactly() throws Exception {
        BudgetWithToken b = createBudget(500.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("RESET", 750.00)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previousTotalLimit").value(500.00))
            .andExpect(jsonPath("$.totalLimit").value(750.00));
    }

    @Test
    void reset_returns400_when_amount_below_quantitySpent() throws Exception {
        BudgetWithToken b = createBudget(500.00);
        String eventId = authorize(b.sessionToken(), 400.00);
        confirm(eventId, 400.00);

        // RESET to 300 → below quantitySpent = 400
        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("RESET", 300.00)))
            .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // RESET_SPENT
    // -------------------------------------------------------------------------

    @Test
    void reset_spent_zeroes_quantitySpent_and_sets_new_limit() throws Exception {
        BudgetWithToken b = createBudget(500.00);
        String eventId = authorize(b.sessionToken(), 200.00);
        confirm(eventId, 200.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("RESET_SPENT", 600.00)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalLimit").value(600.00))
            .andExpect(jsonPath("$.quantitySpent").value(0.00))
            .andExpect(jsonPath("$.availableQuantity").value(600.00))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void fund_returns400_for_negative_amount() throws Exception {
        BudgetWithToken b = createBudget(500.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "operation", "CREDIT",
                    "amount", -50.00
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void fund_returns400_when_operation_missing() throws Exception {
        BudgetWithToken b = createBudget(500.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 100.00))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void fund_returns404_for_unknown_budget() throws Exception {
        mockMvc.perform(post("/api/v1/budgets/{id}/fund", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("CREDIT", 100.00)))
            .andExpect(status().isNotFound());
    }

    @Test
    void fund_returns409_for_cancelled_budget() throws Exception {
        BudgetWithToken b = createBudget(200.00);

        mockMvc.perform(post("/api/v1/budgets/{id}/cancel", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/budgets/{id}/fund", b.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fundJson("CREDIT", 100.00)))
            .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record BudgetWithToken(String id, String sessionToken) {}

    private BudgetWithToken createBudget(double limit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_fund_test",
                    "totalLimit", limit,
                    "currency", "USD",
                    "expiresAt", OffsetDateTime.now().plusHours(2)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new BudgetWithToken(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private String authorize(String sessionToken, double amount) throws Exception {
        String response = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_fund_test",
                    "actionType", "PURCHASE",
                    "description", "fund test",
                    "requestedQuantity", amount,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("eventId").asText();
    }

    private void confirm(String eventId, double amount) throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", amount))))
            .andExpect(status().isOk());
    }

    private String fundJson(String operation, double amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of("operation", operation, "amount", amount));
    }
}
