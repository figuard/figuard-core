package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.service.BudgetSweepService;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that BudgetSweepService correctly expires budgets that were created
 * but never received a first authorize call past their firstAuthorizeDeadline.
 */
class OrphanedBudgetSweepIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired BudgetSweepService sweepService;

    @Test
    void sweep_expiresBudgetThatNeverReceivedAuthorizeCall() throws Exception {
        UUID budgetId = createBudget();

        // Simulate that the firstAuthorizeDeadline has passed by backdating it
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setFirstAuthorizeDeadline(OffsetDateTime.now().minusSeconds(10));
        budgetRepository.save(budget);

        sweepService.sweepOrphanedBudgets();

        AgentBudget expired = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(BudgetStatus.EXPIRED);
    }

    @Test
    void sweep_doesNotExpireBudgetWithinDeadline() throws Exception {
        UUID budgetId = createBudget();

        // firstAuthorizeDeadline is in the future (default 900s from now)
        sweepService.sweepOrphanedBudgets();

        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
    }

    @Test
    void sweep_doesNotExpireBudgetThatHasSpendEvents() throws Exception {
        String[] info = createBudgetAndGetToken();
        UUID budgetId = UUID.fromString(info[0]);
        String sessionToken = info[1];

        // Fire one authorize call — this creates a spend event
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_sweep_test",
                    "actionType", "PURCHASE",
                    "description", "Sweep guard test",
                    "requestedQuantity", "10.00",
                    "currency", "USD",
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk());

        // Backdate the deadline so it would be swept — but it has a spend event
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        budget.setFirstAuthorizeDeadline(OffsetDateTime.now().minusSeconds(10));
        budgetRepository.save(budget);

        sweepService.sweepOrphanedBudgets();

        AgentBudget after = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------

    /** Creates a budget and returns its ID. */
    private UUID createBudget() throws Exception {
        return UUID.fromString(createBudgetAndGetToken()[0]);
    }

    /** Returns [budgetId, sessionToken]. */
    private String[] createBudgetAndGetToken() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_sweep_test_" + UUID.randomUUID(),
            "totalLimit", 100.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        var result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{ node.get("id").asText(), node.get("tokens").get(0).get("sessionToken").asText() };
    }
}
