package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.WebhookEventType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON payload delivered to webhook endpoints.
 *
 * All payloads include: eventType, budgetId, tenantId, timestamp.
 * Threshold events include usage percentages. Spend events include event ID and amounts.
 */
@Component
public class WebhookPayloadBuilder {

    public Map<String, Object> buildThresholdPayload(WebhookEventType eventType,
                                                      AgentBudget budget) {
        Map<String, Object> payload = basePayload(eventType, budget);
        payload.put("totalLimit",      budget.getTotalLimit());
        payload.put("amountSpent",     budget.getAmountSpent());
        payload.put("amountReserved",  budget.getAmountReserved());
        payload.put("availableAmount", budget.availableAmount());
        payload.put("percentUsed",     percentUsed(budget));
        return payload;
    }

    public Map<String, Object> buildSpendDeniedPayload(AgentBudget budget,
                                                        SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_DENIED, budget);
        payload.put("spendEventId",    event.getId());
        payload.put("requestedAmount", event.getRequestedAmount());
        payload.put("denialReason",    event.getDenialReason());
        payload.put("denialMessage",   event.getDenialMessage());
        payload.put("agentId",         event.getAgentId());
        payload.put("currency",        event.getCurrency());
        return payload;
    }

    public Map<String, Object> buildSpendVoidedPayload(AgentBudget budget,
                                                        SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_VOIDED, budget);
        payload.put("spendEventId",    event.getId());
        payload.put("requestedAmount", event.getRequestedAmount());
        payload.put("voidReason",      event.getFailureReason());
        payload.put("agentId",         event.getAgentId());
        payload.put("currency",        event.getCurrency());
        return payload;
    }

    public Map<String, Object> buildBudgetExpiredUnusedPayload(AgentBudget budget) {
        Map<String, Object> payload = basePayload(WebhookEventType.BUDGET_EXPIRED_UNUSED, budget);
        payload.put("expiresAt",       budget.getExpiresAt());
        payload.put("totalLimit",      budget.getTotalLimit());
        return payload;
    }

    public Map<String, Object> buildAnomalyDetectedPayload(AgentBudget budget,
                                                            SpendEvent event,
                                                            BigDecimal baselineMean,
                                                            BigDecimal threshold) {
        Map<String, Object> payload = basePayload(WebhookEventType.ANOMALY_DETECTED, budget);
        payload.put("spendEventId",    event.getId());
        payload.put("requestedAmount", event.getRequestedAmount());
        payload.put("baselineMean",    baselineMean);
        payload.put("threshold",       threshold);
        payload.put("agentId",         event.getAgentId());
        return payload;
    }

    public Map<String, Object> buildSpendConfirmedPayload(AgentBudget budget,
                                                            SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_CONFIRMED, budget);
        payload.put("spendEventId",     event.getId());
        payload.put("requestedAmount",  event.getRequestedAmount());
        payload.put("confirmedAmount",  event.getConfirmedAmount());
        payload.put("agentId",          event.getAgentId());
        payload.put("currency",         event.getCurrency());
        payload.put("totalLimit",       budget.getTotalLimit());
        payload.put("amountSpent",      budget.getAmountSpent());
        payload.put("availableAmount",  budget.availableAmount());
        return payload;
    }

    public Map<String, Object> buildSpendPaymentFailedPayload(AgentBudget budget,
                                                               SpendEvent event) {
        Map<String, Object> payload = basePayload(WebhookEventType.SPEND_PAYMENT_FAILED, budget);
        payload.put("spendEventId",     event.getId());
        payload.put("requestedAmount",  event.getRequestedAmount());
        payload.put("failureReason",    event.getFailureReason());
        payload.put("agentId",          event.getAgentId());
        payload.put("currency",         event.getCurrency());
        payload.put("availableAmount",  budget.availableAmount());
        return payload;
    }

    public Map<String, Object> buildLedgerIntegrityViolationPayload(AgentBudget budget,
                                                                      String violationType,
                                                                      String detail) {
        Map<String, Object> payload = basePayload(WebhookEventType.LEDGER_INTEGRITY_VIOLATION, budget);
        payload.put("violationType",    violationType);
        payload.put("detail",           detail);
        payload.put("totalLimit",       budget.getTotalLimit());
        payload.put("amountSpent",      budget.getAmountSpent());
        payload.put("amountReserved",   budget.getAmountReserved());
        payload.put("availableAmount",  budget.availableAmount());
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

    // -------------------------------------------------------------------------

    private Map<String, Object> basePayload(WebhookEventType eventType, AgentBudget budget) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",  eventType.name());
        payload.put("budgetId",   budget.getId());
        payload.put("tenantId",   budget.getTenant().getId());
        payload.put("userId",     budget.getUserId());
        payload.put("currency",   budget.getCurrency() != null ? budget.getCurrency().trim() : "USD");
        payload.put("timestamp",  OffsetDateTime.now().toString());
        return payload;
    }

    private double percentUsed(AgentBudget budget) {
        if (budget.getTotalLimit().compareTo(BigDecimal.ZERO) == 0) return 0.0;
        BigDecimal used = budget.getAmountSpent().add(budget.getAmountReserved());
        return used.multiply(new BigDecimal("100"))
            .divide(budget.getTotalLimit(), 2, RoundingMode.HALF_UP)
            .doubleValue();
    }
}
