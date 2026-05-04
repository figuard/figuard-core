package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ConcurrencyIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired BudgetAllocationRepository allocationRepository;

    /**
     * 50 threads each request $5 against a flat $100 budget.
     * Exactly 20 must be AUTHORIZED, 30 must be DENIED.
     * Repeated 10 times — must pass every single time.
     * A single failure means pessimistic locking is broken.
     */
    @RepeatedTest(10)
    void concurrentAuthorize_flatBudget_shouldNotExceedBudget() throws Exception {
        String[] budgetInfo = createFlatBudget(100.00);
        String sessionToken = budgetInfo[0];
        UUID budgetId = UUID.fromString(budgetInfo[1]);

        AtomicInteger authorized = new AtomicInteger(0);
        AtomicInteger denied     = new AtomicInteger(0);
        AtomicInteger errors     = new AtomicInteger(0);

        runConcurrent(50, sessionToken, new BigDecimal("5.00"), null, authorized, denied, errors);

        assertThat(errors.get()).isZero();
        assertThat(authorized.get()).isEqualTo(20);
        assertThat(denied.get()).isEqualTo(30);

        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(budget.getAmountReserved()).isEqualByComparingTo("100.00");
        assertThat(budget.getAmountSpent()).isEqualByComparingTo("0.00");
    }

    /**
     * 20 threads each request $5 against a $50 flight allocation (budget total = $50).
     * Exactly 10 must be AUTHORIZED, 10 must be DENIED.
     * Repeated 10 times — a single failure means allocation locking is broken.
     */
    @RepeatedTest(10)
    void concurrentAuthorize_allocationBudget_shouldNotExceedAllocationLimit() throws Exception {
        String[] budgetInfo = createBudgetWithFlightAllocation(50.00);
        String sessionToken = budgetInfo[0];
        UUID budgetId = UUID.fromString(budgetInfo[1]);

        AtomicInteger authorized = new AtomicInteger(0);
        AtomicInteger denied     = new AtomicInteger(0);
        AtomicInteger errors     = new AtomicInteger(0);

        runConcurrent(20, sessionToken, new BigDecimal("5.00"), "flight", authorized, denied, errors);

        assertThat(errors.get()).isZero();
        assertThat(authorized.get()).isEqualTo(10);
        assertThat(denied.get()).isEqualTo(10);

        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        List<BudgetAllocation> allocations =
            allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(budgetId);
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getAmountReserved()).isEqualByComparingTo("50.00");
        assertThat(budget.getAmountReserved()).isEqualByComparingTo("50.00");
    }

    // -------------------------------------------------------------------------

    private void runConcurrent(int threads,
                                String sessionToken,
                                BigDecimal requestAmount,
                                String claimedCategory,
                                AtomicInteger authorized,
                                AtomicInteger denied,
                                AtomicInteger errors) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    var bodyMap = new java.util.HashMap<String, Object>();
                    bodyMap.put("agentId", "agent_" + idx);
                    bodyMap.put("actionType", "PURCHASE");
                    bodyMap.put("description", "Concurrent test purchase");
                    bodyMap.put("requestedAmount", requestAmount);
                    bodyMap.put("currency", "USD");
                    bodyMap.put("idempotencyKey", UUID.randomUUID().toString());
                    if (claimedCategory != null) bodyMap.put("claimedCategory", claimedCategory);

                    MvcResult result = mockMvc.perform(post("/api/v1/authorize")
                            .header("X-Agent-Budget-Key", TEST_API_KEY)
                            .header("X-Session-Token", sessionToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMap)))
                        .andReturn();

                    var node = objectMapper.readTree(result.getResponse().getContentAsString());
                    String decision = node.get("decision").asText();
                    if (SpendDecision.AUTHORIZED.name().equals(decision)) {
                        authorized.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();
    }

    // Returns [sessionToken, budgetId]
    private String[] createFlatBudget(double limit) throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_concurrency_test",
            "totalLimit", limit,
            "currency", "USD",
            "expiresAt", java.time.OffsetDateTime.now().plusHours(2)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{ node.get("sessionToken").asText(), node.get("id").asText() };
    }

    // Returns [sessionToken, budgetId]
    private String[] createBudgetWithFlightAllocation(double limit) throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_alloc_concurrency_test",
            "totalLimit", limit,
            "currency", "USD",
            "expiresAt", java.time.OffsetDateTime.now().plusHours(2)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "allocations", List.of(Map.of(
                "category", "flight",
                "allowedCategories", List.of("flight"),
                "limit", limit
            ))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();

        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{ node.get("sessionToken").asText(), node.get("id").asText() };
    }
}
