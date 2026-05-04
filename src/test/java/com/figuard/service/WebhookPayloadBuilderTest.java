package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every payload builder method includes the required base fields
 * and the event-specific fields that downstream systems rely on.
 */
class WebhookPayloadBuilderTest {

    WebhookPayloadBuilder builder = new WebhookPayloadBuilder();

    Tenant tenant;
    AgentBudget budget;
    SpendEvent event;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        budget = new AgentBudget();
        budget.setId(UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setUserId("user_123");
        budget.setCurrency("USD");
        budget.setTotalLimit(new BigDecimal("500.00"));
        budget.setAmountSpent(new BigDecimal("100.00"));
        budget.setAmountReserved(new BigDecimal("50.00"));

        event = new SpendEvent();
        event.setId(UUID.randomUUID());
        event.setDecision(SpendDecision.AUTHORIZED);
        event.setRequestedAmount(new BigDecimal("80.00"));
        event.setConfirmedAmount(new BigDecimal("78.00"));
        event.setAgentId("agent_001");
        event.setCurrency("USD");
        event.setFailureReason("PAYMENT_DECLINED");
        event.setDenialReason("INSUFFICIENT_FUNDS");
    }

    // -------------------------------------------------------------------------
    // Base fields — present in all payloads
    // -------------------------------------------------------------------------

    @Test
    void allPayloads_containRequiredBaseFields() {
        Map<String, Object> payload = builder.buildThresholdPayload(
            WebhookEventType.BUDGET_50_PCT, budget);

        assertThat(payload).containsKey("eventType");
        assertThat(payload).containsKey("budgetId");
        assertThat(payload).containsKey("tenantId");
        assertThat(payload).containsKey("userId");
        assertThat(payload).containsKey("currency");
        assertThat(payload).containsKey("timestamp");
        assertThat(payload.get("budgetId")).isEqualTo(budget.getId());
        assertThat(payload.get("tenantId")).isEqualTo(tenant.getId());
    }

    // -------------------------------------------------------------------------
    // Threshold payload
    // -------------------------------------------------------------------------

    @Test
    void thresholdPayload_containsUsageFields() {
        Map<String, Object> payload = builder.buildThresholdPayload(
            WebhookEventType.BUDGET_90_PCT, budget);

        assertThat(payload.get("eventType")).isEqualTo("BUDGET_90_PCT");
        assertThat(payload).containsKey("totalLimit");
        assertThat(payload).containsKey("amountSpent");
        assertThat(payload).containsKey("amountReserved");
        assertThat(payload).containsKey("availableAmount");
        assertThat(payload).containsKey("percentUsed");
        // 150/500 = 30%
        assertThat((double) payload.get("percentUsed")).isEqualTo(30.0);
    }

    @Test
    void thresholdPayload_percentUsed_isZero_whenTotalLimitIsZero() {
        budget.setTotalLimit(BigDecimal.ZERO);
        budget.setAmountSpent(BigDecimal.ZERO);
        budget.setAmountReserved(BigDecimal.ZERO);

        Map<String, Object> payload = builder.buildThresholdPayload(
            WebhookEventType.BUDGET_50_PCT, budget);

        assertThat((double) payload.get("percentUsed")).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Spend denied payload
    // -------------------------------------------------------------------------

    @Test
    void spendDeniedPayload_containsEventSpecificFields() {
        Map<String, Object> payload = builder.buildSpendDeniedPayload(budget, event);

        assertThat(payload.get("eventType")).isEqualTo("SPEND_DENIED");
        assertThat(payload.get("spendEventId")).isEqualTo(event.getId());
        assertThat(payload.get("requestedAmount")).isEqualTo(event.getRequestedAmount());
        assertThat(payload.get("denialReason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(payload.get("agentId")).isEqualTo("agent_001");
    }

    // -------------------------------------------------------------------------
    // Spend confirmed payload
    // -------------------------------------------------------------------------

    @Test
    void spendConfirmedPayload_containsConfirmedAmount() {
        Map<String, Object> payload = builder.buildSpendConfirmedPayload(budget, event);

        assertThat(payload.get("eventType")).isEqualTo("SPEND_CONFIRMED");
        assertThat(payload.get("spendEventId")).isEqualTo(event.getId());
        assertThat(payload.get("requestedAmount")).isEqualTo(event.getRequestedAmount());
        assertThat(payload.get("confirmedAmount")).isEqualTo(event.getConfirmedAmount());
        assertThat(payload.get("agentId")).isEqualTo("agent_001");
        assertThat(payload).containsKey("availableAmount");
    }

    // -------------------------------------------------------------------------
    // Spend payment failed payload
    // -------------------------------------------------------------------------

    @Test
    void spendPaymentFailedPayload_containsFailureReason() {
        Map<String, Object> payload = builder.buildSpendPaymentFailedPayload(budget, event);

        assertThat(payload.get("eventType")).isEqualTo("SPEND_PAYMENT_FAILED");
        assertThat(payload.get("spendEventId")).isEqualTo(event.getId());
        assertThat(payload.get("failureReason")).isEqualTo("PAYMENT_DECLINED");
        assertThat(payload).containsKey("availableAmount");
    }

    // -------------------------------------------------------------------------
    // Ledger integrity violation payload
    // -------------------------------------------------------------------------

    @Test
    void ledgerIntegrityViolationPayload_containsViolationDetails() {
        Map<String, Object> payload = builder.buildLedgerIntegrityViolationPayload(
            budget, "OVERSPEND", "amountSpent(510) > totalLimit(500)");

        assertThat(payload.get("eventType")).isEqualTo("LEDGER_INTEGRITY_VIOLATION");
        assertThat(payload.get("violationType")).isEqualTo("OVERSPEND");
        assertThat(payload.get("detail")).isEqualTo("amountSpent(510) > totalLimit(500)");
        assertThat(payload).containsKey("totalLimit");
        assertThat(payload).containsKey("amountSpent");
        assertThat(payload).containsKey("amountReserved");
    }

    // -------------------------------------------------------------------------
    // Budget expired unused payload
    // -------------------------------------------------------------------------

    @Test
    void budgetExpiredUnusedPayload_containsExpiresAt() {
        budget.setExpiresAt(java.time.OffsetDateTime.now().plusHours(1));

        Map<String, Object> payload = builder.buildBudgetExpiredUnusedPayload(budget);

        assertThat(payload.get("eventType")).isEqualTo("BUDGET_EXPIRED_UNUSED");
        assertThat(payload).containsKey("expiresAt");
        assertThat(payload.get("totalLimit")).isEqualTo(budget.getTotalLimit());
    }

    // -------------------------------------------------------------------------
    // Budget resumed payload
    // -------------------------------------------------------------------------

    @Test
    void budgetResumedPayload_containsOverrideReason() {
        Map<String, Object> payload = builder.buildBudgetResumedPayload(
            budget, "Manual override by ops team", "ops@company.com");

        assertThat(payload.get("eventType")).isEqualTo("BUDGET_RESUMED");
        assertThat(payload.get("overrideReason")).isEqualTo("Manual override by ops team");
        assertThat(payload.get("overrideBy")).isEqualTo("ops@company.com");
    }

    @Test
    void budgetResumedPayload_overrideBy_omittedWhenNull() {
        Map<String, Object> payload = builder.buildBudgetResumedPayload(budget, "emergency", null);

        assertThat(payload).doesNotContainKey("overrideBy");
    }

    // -------------------------------------------------------------------------
    // Spend voided payload
    // -------------------------------------------------------------------------

    @Test
    void spendVoidedPayload_containsVoidReason() {
        Map<String, Object> payload = builder.buildSpendVoidedPayload(budget, event);

        assertThat(payload.get("eventType")).isEqualTo("SPEND_VOIDED");
        assertThat(payload.get("voidReason")).isEqualTo("PAYMENT_DECLINED");
        assertThat(payload.get("spendEventId")).isEqualTo(event.getId());
    }
}
