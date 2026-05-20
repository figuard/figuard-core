package com.figuard.service;

import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.repository.EntitlementItemRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Scheduled sweep that finds EntitlementItems whose nextRenewalAt is in the past
 * and triggers renewal for each one.
 *
 * ShedLock ensures only one replica runs the sweep at a time.
 * lockAtMostFor (55 min) < fixedDelay (60 min) — lock always releases before next run.
 *
 * Each item is renewed in its own @Transactional boundary (inside SubscriptionRenewalService)
 * so a single failure does not roll back other items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionRenewalSweepService {

    private final EntitlementItemRepository entitlementItemRepository;
    private final SubscriptionRenewalService renewalService;
    private final MeterRegistry meterRegistry;

    private Counter sweepRunCounter;
    private Counter itemsRenewedCounter;
    private Counter renewalErrorCounter;

    @PostConstruct
    void initMetrics() {
        sweepRunCounter = Counter.builder("figuard.renewal.sweep.runs")
                .description("Number of entitlement renewal sweep executions")
                .register(meterRegistry);

        itemsRenewedCounter = Counter.builder("figuard.renewal.items_renewed")
                .description("Number of entitlement items successfully renewed")
                .register(meterRegistry);

        renewalErrorCounter = Counter.builder("figuard.renewal.errors")
                .description("Number of entitlement renewal failures")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @SchedulerLock(name = "entitlementRenewalSweep", lockAtMostFor = "PT55M", lockAtLeastFor = "PT1M")
    public void sweepDueRenewals() {
        sweepRunCounter.increment();
        doSweep();
    }

    /**
     * Sweep body separated from scheduler annotation so tests can invoke it directly
     * without going through ShedLock.
     */
    public void doSweep() {
        List<EntitlementItem> due = entitlementItemRepository.findItemsDueForRenewal(OffsetDateTime.now());

        if (due.isEmpty()) {
            return;
        }

        log.info("Entitlement renewal sweep: found {} item(s) due for renewal", due.size());

        for (EntitlementItem item : due) {
            try {
                renewalService.renewItem(item);
                itemsRenewedCounter.increment();
            } catch (Exception e) {
                renewalErrorCounter.increment();
                log.error("Failed to renew entitlement item {}: {}", item.getId(), e.getMessage(), e);
            }
        }
    }
}
