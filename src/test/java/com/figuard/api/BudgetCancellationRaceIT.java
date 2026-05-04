package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies that cancel and authorize share the same PESSIMISTIC_WRITE lock.
 *
 * 10 authorize threads + 1 cancel thread fire simultaneously.
 * After all complete:
 *   1. Budget must be CANCELLED
 *   2. amountReserved must exactly equal (authorized event count × $5)
 *      A phantom reservation — authorize writing after cancel — would break this.
 *
 * Repeated 20 times. A single failure means the lock is broken.
 */
class BudgetCancellationRaceIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired SpendEventRepository spendEventRepository;

    @RepeatedTest(20)
    void cancelAndAuthorize_concurrently_budgetAlwaysCancelledWithConsistentReservations()
            throws Exception {

        String[] info = createBudget();
        UUID budgetId = UUID.fromString(info[0]);
        String sessionToken = info[1];

        int authorizeThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(authorizeThreads + 1);
        CountDownLatch ready = new CountDownLatch(authorizeThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // 1 cancel thread
        futures.add(executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                mockMvc.perform(post("/api/v1/budgets/{id}/cancel", budgetId)
                        .header("X-Agent-Budget-Key", TEST_API_KEY))
                    .andReturn();
            } catch (Exception ignored) {}
        }));

        // 10 authorize threads
        for (int i = 0; i < authorizeThreads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    mockMvc.perform(post("/api/v1/authorize")
                            .header("X-Agent-Budget-Key", TEST_API_KEY)
                            .header("X-Session-Token", sessionToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody(idx)))
                        .andReturn();
                } catch (Exception ignored) {}
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // --- Assertions ---

        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();

        // Budget must be CANCELLED — cancel always wins eventually
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
        assertThat(budget.getCancelledAt()).isNotNull();

        // Count actual AUTHORIZED events from the ledger
        List<SpendEvent> events = spendEventRepository.findByBudgetIdOrderByCreatedAtAsc(budgetId);
        long authorizedCount = events.stream()
            .filter(e -> e.getDecision() == SpendDecision.AUTHORIZED)
            .count();

        // amountReserved must equal exactly (authorizedCount × $5.00)
        // If a phantom reservation occurred (authorize wrote after cancel),
        // amountReserved would be higher than the authorized event count implies.
        BigDecimal expectedReserved = new BigDecimal("5.00")
            .multiply(BigDecimal.valueOf(authorizedCount));
        assertThat(budget.getAmountReserved())
            .isEqualByComparingTo(expectedReserved);
    }

    // -------------------------------------------------------------------------

    private String[] createBudget() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_cancel_race_" + UUID.randomUUID(),
            "totalLimit", 100.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        var result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{ node.get("id").asText(), node.get("sessionToken").asText() };
    }

    private String authorizeBody(int idx) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_" + idx,
            "actionType", "PURCHASE",
            "description", "Cancellation race test",
            "requestedAmount", new BigDecimal("5.00"),
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }
}
