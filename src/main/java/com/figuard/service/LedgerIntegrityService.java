package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.SpendEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Continuously verifies three ledger invariants across all active budgets.
 * Runs every 5 minutes via @Scheduled + ShedLock.
 *
 * Three invariants checked:
 *  1. OVERSPEND       — quantitySpent + quantityReserved > totalLimit
 *  2. RESERVATION_MISMATCH — budget.quantityReserved != SUM(AUTHORIZED events.requestedQuantity)
 *  3. SPEND_MISMATCH  — budget.quantitySpent != SUM(CONFIRMED events.confirmedQuantity)
 *
 * On any violation: emits figuard.integrity.violations counter, logs ERROR,
 * fires LEDGER_INTEGRITY_VIOLATION webhook so the ops team is immediately alerted.
 *
 * ⚠️ A flatline on integrity check runs (no figuard.integrity.runs increment for 10+ min)
 * is itself an alert — the scheduled job may have silently stopped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerIntegrityService {

    private final AgentBudgetRepository budgetRepository;
    private final SpendEventRepository eventRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final MeterRegistry meterRegistry;

    private Counter integrityRunCounter;
    private Counter violationCounter;

    @PostConstruct
    void initMetrics() {
        integrityRunCounter = Counter.builder("figuard.integrity.runs")
            .description("Number of ledger integrity check executions")
            .register(meterRegistry);

        violationCounter = Counter.builder("figuard.integrity.violations")
            .description("Number of ledger invariant violations detected")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @SchedulerLock(name = "ledgerIntegrityCheck", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void checkIntegrity() {
        integrityRunCounter.increment();

        // Fetch all non-cancelled, non-expired budgets in pages to avoid loading millions at once
        int page = 0;
        int pageSize = 500;
        List<AgentBudget> batch;

        do {
            batch = budgetRepository.findByStatusIn(
                List.of(BudgetStatus.ACTIVE, BudgetStatus.PAUSED, BudgetStatus.EXHAUSTED),
                PageRequest.of(page++, pageSize));
            for (AgentBudget budget : batch) {
                checkBudget(budget);
            }
        } while (batch.size() == pageSize);
    }

    private void checkBudget(AgentBudget budget) {
        // Invariant 1: Overspend
        BigDecimal consumed = budget.getQuantitySpent().add(budget.getQuantityReserved());
        if (consumed.compareTo(budget.getTotalLimit()) > 0) {
            reportViolation(budget, "OVERSPEND",
                "quantitySpent(" + budget.getQuantitySpent() + ") + quantityReserved("
                    + budget.getQuantityReserved() + ") = " + consumed
                    + " exceeds totalLimit(" + budget.getTotalLimit() + ")");
        }

        // Invariant 2: Reservation mismatch
        BigDecimal sumAuthorized = eventRepository.sumAuthorizedAmountByBudget(budget.getId());
        if (sumAuthorized.compareTo(budget.getQuantityReserved()) != 0) {
            reportViolation(budget, "RESERVATION_MISMATCH",
                "budget.quantityReserved=" + budget.getQuantityReserved()
                    + " but SUM(AUTHORIZED.requestedQuantity)=" + sumAuthorized);
        }

        // Invariant 3: Spend mismatch
        BigDecimal sumConfirmed = eventRepository.sumConfirmedAmountByBudget(budget.getId());
        if (sumConfirmed.compareTo(budget.getQuantitySpent()) != 0) {
            reportViolation(budget, "SPEND_MISMATCH",
                "budget.quantitySpent=" + budget.getQuantitySpent()
                    + " but SUM(CONFIRMED.confirmedQuantity)=" + sumConfirmed);
        }
    }

    private void reportViolation(AgentBudget budget, String violationType, String detail) {
        violationCounter.increment();

        log.error("LEDGER_INTEGRITY_VIOLATION: budgetId={} type={} detail={}",
            budget.getId(), violationType, detail);

        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.LEDGER_INTEGRITY_VIOLATION,
            webhookPayloadBuilder.buildLedgerIntegrityViolationPayload(budget, violationType, detail));
    }
}
