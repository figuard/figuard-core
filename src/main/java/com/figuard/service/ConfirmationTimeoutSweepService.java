package com.figuard.service;

import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.repository.SpendEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
 * Finds AUTHORIZED spend events whose confirmationTimeoutAt has passed and auto-voids them,
 * releasing the reserved funds back to the budget.
 *
 * ShedLock ensures only one instance runs this sweep at a time across all replicas.
 * lockAtMostFor (55s) < fixedDelay (60s) so the lock always releases before the next run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationTimeoutSweepService {

    private final SpendEventRepository eventRepository;
    private final PaymentLifecycleService lifecycleService;
    private final MeterRegistry meterRegistry;

    private Counter sweepRunCounter;
    private Counter eventsProcessedCounter;
    private Counter reservationExpiredCounter;

    @PostConstruct
    void initMetrics() {
        sweepRunCounter = Counter.builder("figuard.sweep.runs")
            .tag("job", "confirmation_timeout")
            .description("Number of confirmation timeout sweep executions")
            .register(meterRegistry);

        eventsProcessedCounter = Counter.builder("figuard.sweep.events_processed")
            .tag("job", "confirmation_timeout")
            .description("Number of stale AUTHORIZED events auto-voided")
            .register(meterRegistry);

        reservationExpiredCounter = Counter.builder("figuard.reservation.expired")
            .description("Number of spend reservations expired due to confirmation timeout")
            .register(meterRegistry);

        // Gauge: current count of in-flight (AUTHORIZED) events — updated on each DB read
        Gauge.builder("figuard.sweep.pending_authorizations", eventRepository,
                SpendEventRepository::countPendingAuthorizations)
            .description("Current number of AUTHORIZED spend events awaiting confirmation")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "confirmationTimeoutSweep", lockAtMostFor = "PT55S", lockAtLeastFor = "PT10S")
    public void sweepExpiredAuthorizations() {
        sweepRunCounter.increment();
        doSweep();
    }

    /**
     * The actual sweep body — separated from the scheduler annotation so tests can invoke
     * it directly without going through ShedLock (whose lockAtLeastFor would block rapid
     * successive calls in the same JVM).
     */
    public void doSweep() {
        List<SpendEvent> stale = eventRepository.findStaleAuthorizations(OffsetDateTime.now());

        if (stale.isEmpty()) {
            return;
        }

        log.info("Confirmation timeout sweep: found {} stale AUTHORIZED events", stale.size());

        for (SpendEvent event : stale) {
            try {
                lifecycleService.autoVoidStaleEvent(event);
                eventsProcessedCounter.increment();
                reservationExpiredCounter.increment();
            } catch (Exception e) {
                // Log and continue — don't let one failure block the rest
                log.error("Failed to auto-void event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
