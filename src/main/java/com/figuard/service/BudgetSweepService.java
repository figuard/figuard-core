package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Expires budgets that were created but never received a first authorize call.
 * These "orphaned" budgets hold no reserved funds and can be safely expired.
 *
 * Runs every 5 minutes (configurable). Each eligible budget is expired within its
 * own transaction so a failure on one budget does not roll back the rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetSweepService {

    private final AgentBudgetRepository budgetRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final MeterRegistry meterRegistry;

    private Counter sweepRunCounter;
    private Counter eventsProcessedCounter;
    private Counter expiringSoonCounter;

    @PostConstruct
    void initMetrics() {
        sweepRunCounter = Counter.builder("figuard.sweep.runs")
            .tag("job", "orphaned_budget")
            .description("Number of orphaned budget sweep executions")
            .register(meterRegistry);

        eventsProcessedCounter = Counter.builder("figuard.sweep.events_processed")
            .tag("job", "orphaned_budget")
            .description("Number of orphaned budgets expired per sweep run")
            .register(meterRegistry);

        expiringSoonCounter = Counter.builder("figuard.sweep.events_processed")
            .tag("job", "expiring_soon")
            .description("Number of BUDGET_EXPIRING_SOON webhooks fired per sweep run")
            .register(meterRegistry);
    }

    // initialDelay = fixedDelay = 5 minutes. Delay-based so runs don't stack if a sweep is slow.
    @Scheduled(
        fixedDelayString  = "${agent-billing.budget.stale-budget-sweep-interval-ms:300000}",
        initialDelayString = "${agent-billing.budget.stale-budget-sweep-interval-ms:300000}"
    )
    public void sweepOrphanedBudgets() {
        sweepRunCounter.increment();

        List<AgentBudget> orphans = budgetRepository.findOrphanedBudgets(OffsetDateTime.now());

        if (orphans.isEmpty()) {
            return;
        }

        log.info("Orphan sweep: found {} budgets past firstAuthorizeDeadline with no spend events",
            orphans.size());

        for (AgentBudget budget : orphans) {
            expireOrphan(budget);
            eventsProcessedCounter.increment();
        }
    }

    @Transactional
    public void expireOrphan(AgentBudget budget) {
        // Re-fetch to get a managed entity inside this transaction
        budgetRepository.findById(budget.getId()).ifPresent(b -> {
            // Guard: only expire if still ACTIVE (concurrent sweep or cancel could have changed it)
            if (b.getStatus() != BudgetStatus.ACTIVE) {
                return;
            }
            b.setStatus(BudgetStatus.EXPIRED);
            budgetRepository.save(b);
            log.info("Budget expired (unused): id={} userId={} firstAuthorizeDeadline={}",
                b.getId(), b.getUserId(), b.getFirstAuthorizeDeadline());
            webhookDispatcher.dispatch(
                b.getTenant().getId(),
                WebhookEventType.BUDGET_EXPIRED_UNUSED,
                webhookPayloadBuilder.buildBudgetExpiredUnusedPayload(b));
        });
    }

    /**
     * Fires BUDGET_EXPIRING_SOON for budgets expiring within 60 minutes.
     *
     * The notification window is [now+55min, now+65min]. Combined with the 5-minute sweep
     * interval, this ensures each budget is notified exactly once. The expiringSoonNotified
     * flag provides an additional idempotency guard in case of sweep overlap or restart.
     */
    @Scheduled(
        fixedDelayString   = "${agent-billing.budget.stale-budget-sweep-interval-ms:300000}",
        initialDelayString = "${agent-billing.budget.stale-budget-sweep-interval-ms:300000}"
    )
    public void sweepExpiringSoon() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AgentBudget> expiring = budgetRepository.findExpiringSoon(
            now.plusMinutes(55), now.plusMinutes(65));

        if (expiring.isEmpty()) {
            return;
        }

        log.info("Expiry-soon sweep: found {} budgets expiring within 60 minutes", expiring.size());

        for (AgentBudget budget : expiring) {
            notifyExpiringSoon(budget);
            expiringSoonCounter.increment();
        }
    }

    @Transactional
    public void notifyExpiringSoon(AgentBudget budget) {
        budgetRepository.findById(budget.getId()).ifPresent(b -> {
            // Guard: skip if status changed or already notified since the batch was loaded
            if (b.isExpiringSoonNotified()) {
                return;
            }
            if (b.getStatus() != BudgetStatus.ACTIVE && b.getStatus() != BudgetStatus.PAUSED) {
                return;
            }
            b.setExpiringSoonNotified(true);
            budgetRepository.save(b);
            log.info("BUDGET_EXPIRING_SOON: id={} expiresAt={}", b.getId(), b.getExpiresAt());
            webhookDispatcher.dispatch(
                b.getTenant().getId(),
                WebhookEventType.BUDGET_EXPIRING_SOON,
                webhookPayloadBuilder.buildBudgetExpiringSoonPayload(b));
        });
    }
}
