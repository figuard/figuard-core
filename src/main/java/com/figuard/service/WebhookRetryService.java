package com.figuard.service;

import com.figuard.domain.entity.WebhookDelivery;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.WebhookDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sweeps FAILED webhook deliveries and re-attempts them with exponential backoff.
 *
 * The WebhookDispatcher makes 4 immediate attempts (0s, 1s, 2s, 4s) before writing
 * FAILED. This service handles everything after that, up to 10 total attempts.
 *
 * Backoff schedule (based on attemptCount at time of scheduling next retry):
 *   attempt 4  → retry after 1 min
 *   attempt 5  → retry after 2 min
 *   attempt 6  → retry after 4 min
 *   attempt 7  → retry after 8 min
 *   attempt 8  → retry after 16 min
 *   attempt 9  → retry after 32 min
 *   attempt 10 → terminal — no more retries
 *
 * For entitlement.renewed deliveries: if attemptCount reaches 7 (3 sweep failures after
 * initial dispatch) and the delivery is still failing, a RENEWAL_TOKEN_DELIVERY_FAILED
 * alert is dispatched to all tenant webhook configs subscribed to that event type.
 * This tells the operator to call POST /entitlements/{id}/rotate-tokens manually to
 * generate fresh tokens before the old ones are acted on with stale data.
 *
 * ShedLock prevents duplicate execution across replicas.
 * lockAtMostFor (55s) < fixedDelay (60s) — lock always releases before next run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookRetryService {

    // entitlement.renewed is stored as this string in the event_type column.
    // Once the entitlement feature ships this constant should move to WebhookEventType.
    private static final String ENTITLEMENT_RENEWED = "entitlement.renewed";

    // After this many total attempts on a renewal delivery, fire the operator alert.
    private static final int RENEWAL_ALERT_THRESHOLD = 7;

    // Maximum total attempts (dispatcher 4 + sweep 6 = 10).
    private static final int MAX_ATTEMPTS = 10;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDispatcher webhookDispatcher;

    private Counter retriedCounter;
    private Counter recoveredCounter;
    private Counter terminalCounter;
    private Counter renewalAlertCounter;

    @PostConstruct
    void initMetrics(MeterRegistry meterRegistry) {
        retriedCounter = Counter.builder("figuard.webhook.retry.attempts")
            .description("Total sweep retry attempts")
            .register(meterRegistry);
        recoveredCounter = Counter.builder("figuard.webhook.retry.recovered")
            .description("Deliveries that succeeded on a sweep retry")
            .register(meterRegistry);
        terminalCounter = Counter.builder("figuard.webhook.retry.terminal")
            .description("Deliveries that exhausted all retries")
            .register(meterRegistry);
        renewalAlertCounter = Counter.builder("figuard.webhook.retry.renewal_alerts")
            .description("RENEWAL_TOKEN_DELIVERY_FAILED alerts fired")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "webhookRetrySweep", lockAtMostFor = "PT55S", lockAtLeastFor = "PT10S")
    public void sweep() {
        List<WebhookDelivery> due = deliveryRepository.findRetriableDeliveries(OffsetDateTime.now());

        if (due.isEmpty()) return;

        log.info("Webhook retry sweep: {} deliveries due", due.size());

        for (WebhookDelivery delivery : due) {
            retryOne(delivery);
        }
    }

    @Transactional
    public void retryOne(WebhookDelivery delivery) {
        retriedCounter.increment();

        boolean succeeded = webhookDispatcher.attemptSingleRetry(delivery);

        if (succeeded) {
            recoveredCounter.increment();
            return;
        }

        int attempts = delivery.getAttemptCount(); // already incremented by attemptSingleRetry

        if (attempts >= MAX_ATTEMPTS) {
            // Terminal — no more retries. Leave status=FAILED, nextRetryAt=null.
            delivery.setNextRetryAt(null);
            deliveryRepository.save(delivery);
            terminalCounter.increment();
            log.error("Webhook delivery terminal after {} attempts: deliveryId={} event={}",
                attempts, delivery.getId(), delivery.getEventType());

            // Special case: if this was an entitlement.renewed delivery and we haven't alerted yet,
            // fire the operator alert now so they know to call rotate-tokens manually.
            fireRenewalAlertIfNeeded(delivery);
            return;
        }

        // Schedule next retry with exponential backoff
        Duration backoff = backoffFor(attempts);
        delivery.setNextRetryAt(OffsetDateTime.now().plus(backoff));
        deliveryRepository.save(delivery);

        log.info("Webhook retry scheduled: deliveryId={} event={} attempt={} nextRetryAt={}",
            delivery.getId(), delivery.getEventType(), attempts, delivery.getNextRetryAt());

        // Early renewal alert — don't wait until terminal if renewal tokens are stale
        if (attempts >= RENEWAL_ALERT_THRESHOLD) {
            fireRenewalAlertIfNeeded(delivery);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Exponential backoff starting at 1 minute for the first sweep attempt (attemptCount=5).
     * Doubles each attempt: 1m, 2m, 4m, 8m, 16m, 32m — caps at 6 hours for safety.
     */
    private Duration backoffFor(int attemptCount) {
        // attemptCount=5 → 2^0 = 1 min, 6 → 2 min, 7 → 4 min, 8 → 8 min, 9 → 16 min
        long minutes = (long) Math.pow(2, attemptCount - 5);
        minutes = Math.min(minutes, 360); // cap at 6 hours
        return Duration.ofMinutes(minutes);
    }

    /**
     * Fires RENEWAL_TOKEN_DELIVERY_FAILED to all tenant webhook configs subscribed to
     * that event type. Only fires once per delivery (renewalAlertSent guard).
     * Only applies to entitlement.renewed deliveries with a webhookConfig (tenant-backed).
     */
    private void fireRenewalAlertIfNeeded(WebhookDelivery delivery) {
        if (!ENTITLEMENT_RENEWED.equals(delivery.getEventType())) return;
        if (delivery.isRenewalAlertSent()) return;
        if (delivery.getWebhookConfig() == null) return; // no tenant context for direct-URL deliveries

        delivery.setRenewalAlertSent(true);
        deliveryRepository.save(delivery);
        renewalAlertCounter.increment();

        Map<String, Object> alertPayload = Map.of(
            "type", WebhookEventType.RENEWAL_TOKEN_DELIVERY_FAILED.name(),
            "failed_delivery_id", delivery.getId().toString(),
            "event_type", delivery.getEventType(),
            "attempt_count", delivery.getAttemptCount(),
            "message", "Renewal webhook delivery has failed repeatedly. " +
                "Call POST /entitlements/{id}/rotate-tokens to issue fresh tokens " +
                "and trigger a new delivery attempt.",
            "fired_at", OffsetDateTime.now().toString()
        );

        webhookDispatcher.dispatch(
            delivery.getWebhookConfig().getTenant().getId(),
            WebhookEventType.RENEWAL_TOKEN_DELIVERY_FAILED,
            alertPayload);

        log.error("RENEWAL_TOKEN_DELIVERY_FAILED alert fired: deliveryId={} attemptCount={}",
            delivery.getId(), delivery.getAttemptCount());
    }
}
