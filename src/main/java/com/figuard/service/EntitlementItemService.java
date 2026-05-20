package com.figuard.service;

import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.EntitlementStateTransition;
import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.EntitlementItemRepository;
import com.figuard.domain.repository.EntitlementStateTransitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Manages entitlement item state transitions and the webhooks they trigger.
 * Called by EntitlementEnforcementService after every consumption update.
 *
 * State machine (v1):
 *   NORMAL → APPROACHING  (consumed >= warnAtPercentage%)
 *   APPROACHING → LIMIT_REACHED  (consumed >= 100%)
 *   Any state → NORMAL  (on renewal reset)
 *
 * Dedup rule: only one state transition webhook fires per (item, toState) per period.
 * Checked via lastStateTransitionAt — if the current state already matches and
 * lastStateTransitionAt is in the current period, webhook is suppressed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementItemService {

    private final EntitlementItemRepository entitlementItemRepository;
    private final EntitlementStateTransitionRepository transitionRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;

    /**
     * Evaluate whether a state transition should occur after consumption was updated.
     * Called within the same transaction as the consumption update.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluateStateTransition(EntitlementItem item) {
        EntitlementState current = item.getState();
        EntitlementState target = computeTargetState(item);

        if (target == current) return; // no transition needed

        recordTransition(item, current, target, "CONSUMPTION");
        item.setState(target);
        item.setLastStateTransitionAt(OffsetDateTime.now());
        entitlementItemRepository.save(item);

        log.info("EntitlementItem state transition: id={} subscriptionId={} {}→{} consumed={}%",
                item.getId(), item.getSubscription().getId(),
                current, target, item.consumedPercentage());

        dispatchStateWebhook(item, current, target);
    }

    /**
     * Reset state to NORMAL after renewal. Separate from evaluateStateTransition
     * because renewal always forces NORMAL regardless of consumed amount
     * (which has just been zeroed out).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resetStateAfterRenewal(EntitlementItem item) {
        EntitlementState previous = item.getState();
        item.setState(EntitlementState.NORMAL);
        item.setLastStateTransitionAt(null); // clear so next period's transitions fire fresh
        entitlementItemRepository.save(item);

        if (previous != EntitlementState.NORMAL) {
            recordTransition(item, previous, EntitlementState.NORMAL, "RENEWAL");
            log.info("EntitlementItem reset to NORMAL after renewal: id={} previousState={}",
                    item.getId(), previous);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private EntitlementState computeTargetState(EntitlementItem item) {
        int pct = item.consumedPercentage();
        if (pct >= 100) return EntitlementState.LIMIT_REACHED;
        if (pct >= item.getWarnAtPercentage()) return EntitlementState.APPROACHING;
        return EntitlementState.NORMAL;
    }

    private void recordTransition(EntitlementItem item,
                                   EntitlementState from,
                                   EntitlementState to,
                                   String reason) {
        EntitlementStateTransition t = new EntitlementStateTransition();
        t.setEntitlementItem(item);
        t.setFromState(from);
        t.setToState(to);
        t.setConsumedPercentageAtTransition(item.consumedPercentage());
        t.setTriggerReason(reason);
        transitionRepository.save(t);
    }

    private void dispatchStateWebhook(EntitlementItem item,
                                       EntitlementState from,
                                       EntitlementState to) {
        WebhookEventType eventType = to == EntitlementState.LIMIT_REACHED
                ? WebhookEventType.ENTITLEMENT_LIMIT_REACHED
                : WebhookEventType.ENTITLEMENT_STATE_CHANGED;

        Map<String, Object> payload = webhookPayloadBuilder.buildEntitlementStateChangedPayload(
                item, from, to);

        webhookDispatcher.dispatch(
                item.getSubscription().getTenant().getId(),
                eventType,
                payload);
    }
}
