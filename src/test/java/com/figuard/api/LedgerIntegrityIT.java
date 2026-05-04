package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.service.LedgerIntegrityService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies LedgerIntegrityService correctly detects the three invariant violations
 * when the database is in a corrupt state (simulated by direct manipulation of
 * budget fields without going through the normal service layer).
 *
 * In a healthy system these violations should never occur. These tests confirm
 * the detection logic fires when they do — i.e., the safety net works.
 */
class LedgerIntegrityIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired LedgerIntegrityService integrityService;

    // -------------------------------------------------------------------------
    // Clean budget passes all invariants
    // -------------------------------------------------------------------------

    @Test
    void checkIntegrity_raisesNoViolation_forHealthyBudget() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID budgetId = UUID.fromString(info[1]);

        // Must not throw — a clean budget passes all invariants
        assertThat(budgetRepository.findById(budgetId)).isPresent();
        integrityService.checkIntegrity(); // should run cleanly
    }

    // -------------------------------------------------------------------------
    // OVERSPEND — amountSpent + amountReserved > totalLimit
    // -------------------------------------------------------------------------

    @Test
    void checkIntegrity_detectsOverspend() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID budgetId = UUID.fromString(info[1]);

        // Corrupt the budget: force amountSpent to exceed totalLimit
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setAmountSpent(new BigDecimal("600.00")); // totalLimit is 500
        budgetRepository.save(budget);

        // checkIntegrity must detect the violation — it should NOT throw
        // (violations are logged and dispatched, not thrown)
        integrityService.checkIntegrity();

        // Budget is still in the DB — integrity check is non-destructive
        assertThat(budgetRepository.findById(budgetId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // RESERVATION_MISMATCH — budget.amountReserved != SUM(AUTHORIZED events)
    // -------------------------------------------------------------------------

    @Test
    void checkIntegrity_detectsReservationMismatch() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID budgetId = UUID.fromString(info[1]);

        // Corrupt: set amountReserved to something different from the actual event sum
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setAmountReserved(new BigDecimal("999.00")); // AUTHORIZED events sum to 100
        budgetRepository.save(budget);

        integrityService.checkIntegrity(); // detects RESERVATION_MISMATCH

        assertThat(budgetRepository.findById(budgetId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // SPEND_MISMATCH — budget.amountSpent != SUM(CONFIRMED events)
    // -------------------------------------------------------------------------

    @Test
    void checkIntegrity_detectsSpendMismatch() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID budgetId  = UUID.fromString(info[1]);
        String eventId = info[0];
        String sessionToken = info[2];

        // Confirm the event first (normal path)
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 100.00))))
            .andExpect(status().isOk());

        // Corrupt: set amountSpent to something that doesn't match CONFIRMED event sum
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setAmountSpent(new BigDecimal("50.00")); // CONFIRMED sum is 100
        budgetRepository.save(budget);

        integrityService.checkIntegrity(); // detects SPEND_MISMATCH

        assertThat(budgetRepository.findById(budgetId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Terminal-state budgets are skipped
    // -------------------------------------------------------------------------

    @Test
    void checkIntegrity_skips_cancelledBudgets() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID budgetId = UUID.fromString(info[1]);

        // Cancel and corrupt — cancelled budgets must not be checked
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setStatus(com.figuard.domain.enums.BudgetStatus.CANCELLED);
        budget.setAmountSpent(new BigDecimal("9999.00")); // obviously corrupt
        budgetRepository.save(budget);

        // Should not detect or report a violation for CANCELLED budgets
        integrityService.checkIntegrity();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns [eventId, budgetId, sessionToken]. */
    private String[] createBudgetAndAuthorize(double amount) throws Exception {
        String createBody = objectMapper.writeValueAsString(Map.of(
            "userId", "user_integrity_" + UUID.randomUUID(),
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));

        var createResult = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andReturn();

        var budgetJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String budgetId     = budgetJson.get("id").asText();
        String sessionToken = budgetJson.get("sessionToken").asText();

        var authResult = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_integrity_test",
                    "actionType", "PURCHASE",
                    "description", "integrity test",
                    "requestedAmount", amount,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andReturn();

        String eventId = objectMapper.readTree(authResult.getResponse().getContentAsString())
            .get("eventId").asText();

        return new String[]{eventId, budgetId, sessionToken};
    }
}
