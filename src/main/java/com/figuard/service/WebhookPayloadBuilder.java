package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.DelegatedToken;
import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.WebhookEventType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the JSON payload delivered to webhook endpoints.
 *
 * All payloads include: eventType, budgetId, tenantId, timestamp.
 * Threshold events include usage percentages. Spend events include event ID and quantities.
 */
@Component
public class WebhookPayloadBuilder {

    public Map<String, Object> buildThresholdPayload(WebhookEventType eventType,
                                                      AgentBudget budget) {
        Map<String, Object> payload = basePayload(eventType, budget);
        payload.put("totalLimit",        budget.getTotalLimit());
        payload.put("quantitySpent",     budget.getQuantitySpent());
        payload.put("quantityReserved",  budget.getQuantityReserved());
        payload.put("availableQuantity", budget.availableQuantity());
        payload.put("percentUsed",       percentUsed(budget));
        return payload;
    }

    public Map<String, Object> buildSpendDeniedPayload(AgentBudget budget,
                                                        SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_DENIED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("denialReason",      event.getDenialReason());
        payload.put("denialMessage",     event.getDenialMessage());
        payload.put("agentId",           event.getAgentId());
        payload.put("currency",          event.getCurrency());
        return payload;
    }

    public Map<String, Object> buildSpendVoidedPayload(AgentBudget budget,
                                                        SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_VOIDED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("voidReason",        event.getFailureReason());
        payload.put("agentId",           event.getAgentId());
        payload.put("currency",          event.getCurrency());
        return payload;
    }

    public Map<String, Object> buildBudgetExpiredUnusedPayload(AgentBudget budget) {
        Map<String, Object> payload = basePayload(WebhookEventType.BUDGET_EXPIRED_UNUSED, budget);
        payload.put("expiresAt",         budget.getExpiresAt());
        payload.put("totalLimit",        budget.getTotalLimit());
        return payload;
    }

    public Map<String, Object> buildAnomalyDetectedPayload(AgentBudget budget,
                                                            SpendEvent event,
                                                            BigDecimal baselineMean,
                                                            BigDecimal threshold) {
        Map<String, Object> payload = basePayload(WebhookEventType.ANOMALY_DETECTED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("baselineMean",      baselineMean);
        payload.put("threshold",         threshold);
        payload.put("agentId",           event.getAgentId());
        return payload;
    }

    public Map<String, Object> buildSpendConfirmedPayload(AgentBudget budget,
                                                            SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_CONFIRMED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("confirmedQuantity", event.getConfirmedQuantity());
        payload.put("agentId",           event.getAgentId());
        payload.put("currency",          event.getCurrency());
        payload.put("totalLimit",        budget.getTotalLimit());
        payload.put("quantitySpent",     budget.getQuantitySpent());
        payload.put("availableQuantity", budget.availableQuantity());
        return payload;
    }

    public Map<String, Object> buildSpendPaymentFailedPayload(AgentBudget budget,
                                                               SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_PAYMENT_FAILED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("failureReason",     event.getFailureReason());
        payload.put("agentId",           event.getAgentId());
        payload.put("currency",          event.getCurrency());
        payload.put("availableQuantity", budget.availableQuantity());
        return payload;
    }

    public Map<String, Object> buildLedgerIntegrityViolationPayload(AgentBudget budget,
                                                                      String violationType,
                                                                      String detail) {
        Map<String, Object> payload = basePayload(WebhookEventType.LEDGER_INTEGRITY_VIOLATION, budget);
        payload.put("violationType",     violationType);
        payload.put("detail",            detail);
        payload.put("totalLimit",        budget.getTotalLimit());
        payload.put("quantitySpent",     budget.getQuantitySpent());
        payload.put("quantityReserved",  budget.getQuantityReserved());
        payload.put("availableQuantity", budget.availableQuantity());
        return payload;
    }

    public Map<String, Object> buildBudgetResumedPayload(AgentBudget budget,
                                                          String overrideReason,
                                                          String overrideBy) {
        Map<String, Object> payload = basePayload(WebhookEventType.BUDGET_RESUMED, budget);
        payload.put("overrideReason",  overrideReason);
        if (overrideBy != null) {
            payload.put("overrideBy", overrideBy);
        }
        return payload;
    }

    /**
     * BUDGET_PAUSED — fired when a budget is paused, either by anomaly detection or manually.
     * Includes the pause reason so orchestrators can distinguish the two cases.
     */
    public Map<String, Object> buildBudgetPausedPayload(AgentBudget budget, String reason) {
        Map<String, Object> payload = basePayload(WebhookEventType.BUDGET_PAUSED, budget);
        payload.put("reason",            reason);
        payload.put("totalLimit",        budget.getTotalLimit());
        payload.put("quantitySpent",     budget.getQuantitySpent());
        payload.put("availableQuantity", budget.availableQuantity());
        return payload;
    }

    /**
     * ALLOCATION_EXHAUSTED — fired when a category allocation has no remaining capacity.
     * Helps orchestrators reroute remaining work to a different category.
     *
     * @param triggeringEventId  ID of the SPEND_DENIED event that triggered this alert
     * @param requestedQuantity  the quantity that was denied (last straw that caused exhaustion)
     */
    public Map<String, Object> buildAllocationExhaustedPayload(AgentBudget budget,
                                                                 BudgetAllocation allocation,
                                                                 UUID triggeringEventId,
                                                                 BigDecimal requestedQuantity) {
        Map<String, Object> payload = basePayload(WebhookEventType.ALLOCATION_EXHAUSTED, budget);
        payload.put("allocationId",      allocation.getId());
        payload.put("category",          allocation.getCategory());
        payload.put("allocationLimit",   allocation.getTotalLimit());
        payload.put("quantitySpent",     allocation.getQuantitySpent());
        payload.put("triggeringEventId", triggeringEventId);
        payload.put("requestedQuantity", requestedQuantity);
        return payload;
    }

    /**
     * VELOCITY_LIMIT_EXCEEDED — fired on the first violation of a rolling-window rate limit.
     * Subsequent violations in the same window are silently denied without re-firing.
     *
     * @param event          the VELOCITY_LIMIT_EXCEEDED SpendEvent written to the ledger
     * @param violatedLimit  human-readable description of which limit was hit (e.g. "maxPerMinute=2")
     */
    public Map<String, Object> buildVelocityLimitExceededPayload(AgentBudget budget,
                                                                   SpendEvent event,
                                                                   String violatedLimit) {
        Map<String, Object> payload = basePayload(WebhookEventType.VELOCITY_LIMIT_EXCEEDED, budget);
        payload.put("spendEventId",      event.getId());
        payload.put("requestedQuantity", event.getRequestedQuantity());
        payload.put("violatedLimit",     violatedLimit);
        payload.put("agentId",           event.getAgentId());
        payload.put("currency",          event.getCurrency());
        return payload;
    }

    /**
     * BUDGET_EXPIRING_SOON — fired 60 minutes before budget expiry.
     * Gives orchestrators a window to extend the budget or initiate graceful shutdown.
     */
    public Map<String, Object> buildBudgetExpiringSoonPayload(AgentBudget budget) {
        Map<String, Object> payload = basePayload(WebhookEventType.BUDGET_EXPIRING_SOON, budget);
        payload.put("expiresAt",         budget.getExpiresAt());
        payload.put("totalLimit",        budget.getTotalLimit());
        payload.put("quantitySpent",     budget.getQuantitySpent());
        payload.put("availableQuantity", budget.availableQuantity());
        return payload;
    }

    /**
     * DELEGATION_TOKEN_REVOKED — fired when a delegation token is explicitly revoked.
     * Orchestrators should stop routing work to the sub-agent that held this token.
     */
    public Map<String, Object> buildDelegationTokenRevokedPayload(DelegatedToken token) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",      WebhookEventType.DELEGATION_TOKEN_REVOKED.name());
        payload.put("delegationTokenId", token.getId());
        payload.put("parentBudgetId", token.getParentBudget().getId());
        payload.put("tenantId",       token.getTenant().getId());
        payload.put("label",          token.getLabel());
        payload.put("revokedAt",      token.getRevokedAt() != null ? token.getRevokedAt().toString() : null);
        payload.put("timestamp",      OffsetDateTime.now().toString());
        return payload;
    }

    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Entitlement payloads
    // -------------------------------------------------------------------------

    public Map<String, Object> buildEntitlementStateChangedPayload(
            EntitlementItem item, EntitlementState from, EntitlementState to) {
        Map<String, Object> payload = baseEntitlementPayload(
                to == EntitlementState.LIMIT_REACHED
                        ? WebhookEventType.ENTITLEMENT_LIMIT_REACHED
                        : WebhookEventType.ENTITLEMENT_STATE_CHANGED,
                item);
        payload.put("fromState",          from.name());
        payload.put("toState",            to.name());
        payload.put("consumedPercentage", item.consumedPercentage());
        return payload;
    }

    public Map<String, Object> buildEntitlementRenewedPayload(EntitlementItem item,
                                                               java.math.BigDecimal periodConsumed) {
        Map<String, Object> payload = baseEntitlementPayload(WebhookEventType.ENTITLEMENT_RENEWED, item);
        payload.put("previousPeriodConsumed", periodConsumed);
        payload.put("newPeriodLimit",         item.getLimitQuantity());
        payload.put("nextRenewalAt",          item.getNextRenewalAt().toString());
        return payload;
    }

    private Map<String, Object> baseEntitlementPayload(WebhookEventType eventType, EntitlementItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",             eventType.name());
        payload.put("entitlementItemId",     item.getId());
        payload.put("subscriptionId",        item.getSubscription().getId());
        payload.put("tenantId",              item.getSubscription().getTenant().getId());
        payload.put("entitlementName",       item.getName());
        payload.put("limitUnit",             item.getLimitUnit());
        payload.put("limitQuantity",         item.getLimitQuantity());
        payload.put("currentPeriodConsumed", item.getCurrentPeriodConsumed());
        payload.put("remaining",             item.remaining());
        payload.put("state",                 item.getState().name());
        payload.put("timestamp",             OffsetDateTime.now().toString());
        return payload;
    }

    // -------------------------------------------------------------------------

    private Map<String, Object> basePayload(WebhookEventType eventType, AgentBudget budget) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",  eventType.name());
        payload.put("budgetId",   budget.getId());
        payload.put("tenantId",   budget.getTenant().getId());
        payload.put("userId",     budget.getUserId());
        payload.put("currency",   budget.getCurrency() != null ? budget.getCurrency().trim() : null);
        payload.put("unit",       budget.getUnit());
        payload.put("timestamp",  OffsetDateTime.now().toString());
        return payload;
    }

    private double percentUsed(AgentBudget budget) {
        if (budget.getTotalLimit().compareTo(BigDecimal.ZERO) == 0) return 0.0;
        BigDecimal used = budget.getQuantitySpent().add(budget.getQuantityReserved());
        return used.multiply(new BigDecimal("100"))
            .divide(budget.getTotalLimit(), 2, RoundingMode.HALF_UP)
            .doubleValue();
    }
}
