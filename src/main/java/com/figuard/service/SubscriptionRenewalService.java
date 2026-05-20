package com.figuard.service;

import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.SubscriptionRenewalLog;
import com.figuard.domain.enums.RenewalPeriod;
import com.figuard.domain.enums.RenewalResult;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.EntitlementItemRepository;
import com.figuard.domain.repository.SubscriptionRenewalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Renews a single EntitlementItem:
 *   1. Snapshot the period's consumed quantity for the renewal log.
 *   2. Reset currentPeriodConsumed to zero.
 *   3. Advance nextRenewalAt by one period.
 *   4. Reset entitlement state to NORMAL (clears lastStateTransitionAt).
 *   5. Fire ENTITLEMENT_RENEWED webhook asynchronously.
 *   6. Write a SubscriptionRenewalLog record.
 *
 * Called from SubscriptionRenewalSweepService, one item at a time, each in its
 * own @Transactional boundary so a single failure cannot roll back other items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionRenewalService {

    private final EntitlementItemRepository entitlementItemRepository;
    private final SubscriptionRenewalLogRepository renewalLogRepository;
    private final EntitlementItemService entitlementItemService;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;

    @Transactional
    public void renewItem(EntitlementItem stale) {
        // Re-fetch with pessimistic lock to prevent concurrent renewal races
        EntitlementItem item = entitlementItemRepository.findByIdWithLock(stale.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "EntitlementItem disappeared during renewal: " + stale.getId()));

        // Guard: another replica may have already renewed this item
        if (!item.getNextRenewalAt().isBefore(OffsetDateTime.now())) {
            log.debug("EntitlementItem {} already renewed by another replica, skipping", item.getId());
            return;
        }

        BigDecimal periodConsumed = item.getCurrentPeriodConsumed();
        OffsetDateTime renewedAt   = OffsetDateTime.now();
        OffsetDateTime nextRenewal = computeNextRenewal(item);

        // Reset consumed and advance renewal window
        item.setCurrentPeriodConsumed(BigDecimal.ZERO);
        item.setNextRenewalAt(nextRenewal);
        entitlementItemRepository.save(item);

        // Reset state machine (forces NORMAL, clears lastStateTransitionAt)
        entitlementItemService.resetStateAfterRenewal(item);

        log.info("EntitlementItem renewed: id={} subscriptionId={} periodConsumed={} nextRenewalAt={}",
                item.getId(), item.getSubscription().getId(), periodConsumed, nextRenewal);

        // Fire ENTITLEMENT_RENEWED webhook — async, must not fail the transaction
        RenewalResult webhookResult = RenewalResult.SUCCESS;
        try {
            Map<String, Object> payload = webhookPayloadBuilder.buildEntitlementRenewedPayload(item, periodConsumed);
            webhookDispatcher.dispatch(
                    item.getSubscription().getTenant().getId(),
                    WebhookEventType.ENTITLEMENT_RENEWED,
                    payload);
        } catch (Exception e) {
            webhookResult = RenewalResult.WEBHOOK_FAILED;
            log.error("ENTITLEMENT_RENEWED webhook dispatch failed for item {}: {}", item.getId(), e.getMessage());
        }

        // Write renewal log for audit / billing reconciliation
        SubscriptionRenewalLog logEntry = new SubscriptionRenewalLog();
        logEntry.setSubscription(item.getSubscription());
        logEntry.setEntitlementItem(item);
        logEntry.setPeriodConsumedQuantity(periodConsumed);
        logEntry.setNewPeriodLimit(item.getLimitQuantity());
        logEntry.setRenewalExecutedAt(renewedAt);
        logEntry.setNewPeriodEndsAt(nextRenewal);
        logEntry.setResult(webhookResult);
        renewalLogRepository.save(logEntry);
    }

    // -------------------------------------------------------------------------
    // Period arithmetic
    // -------------------------------------------------------------------------

    private OffsetDateTime computeNextRenewal(EntitlementItem item) {
        OffsetDateTime base = item.getNextRenewalAt();
        return switch (item.getRenewalPeriod()) {
            case MONTHLY   -> base.plusMonths(1);
            case QUARTERLY -> base.plusMonths(3);
            case ANNUALLY  -> base.plusYears(1);
        };
    }
}
