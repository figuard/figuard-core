package com.figuard.service;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.SpendEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Extended lifecycle tests covering gaps in PaymentLifecycleServiceTest:
 * autoVoidStaleEvent, webhook dispatch verification, and edge-case lifecycle transitions.
 */
@ExtendWith(MockitoExtension.class)
class PaymentLifecycleServiceExtendedTest {

    @Mock SpendEventRepository eventRepository;
    @Mock AgentBudgetRepository budgetRepository;
    @Mock BudgetAllocationRepository allocationRepository;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;
    @Mock BudgetMapper budgetMapper;
    @Mock AnomalyBaselineService anomalyBaselineService;

    @InjectMocks PaymentLifecycleService service;

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
        budget.setAmountReserved(new BigDecimal("100.00"));
        budget.setAmountSpent(BigDecimal.ZERO);

        event = new SpendEvent();
        event.setId(UUID.randomUUID());
        event.setTenant(tenant);
        event.setBudget(budget);
        event.setDecision(SpendDecision.AUTHORIZED);
        event.setRequestedAmount(new BigDecimal("100.00"));
        event.setConfirmationTimeoutAt(OffsetDateTime.now().plusMinutes(5));
    }

    // -------------------------------------------------------------------------
    // autoVoidStaleEvent — used by ConfirmationTimeoutSweepService
    // -------------------------------------------------------------------------

    @Test
    void autoVoidStaleEvent_releasesReservationAndSetsVoided() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);

        service.autoVoidStaleEvent(event);

        assertThat(event.getDecision()).isEqualTo(SpendDecision.VOIDED);
        assertThat(event.getFailureReason()).isEqualTo("CONFIRMATION_TIMEOUT");
        assertThat(budget.getAmountReserved()).isEqualByComparingTo("0.00");
    }

    @Test
    void autoVoidStaleEvent_isNoOp_whenEventAlreadyVoided() {
        // Simulates a race: sweep finds event, but another thread already voided it
        event.setDecision(SpendDecision.VOIDED);
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        service.autoVoidStaleEvent(event);

        // Budget must not be double-modified
        verify(budgetRepository, never()).findByIdWithLock(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void autoVoidStaleEvent_isNoOp_whenEventAlreadyConfirmed() {
        event.setDecision(SpendDecision.CONFIRMED);
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        service.autoVoidStaleEvent(event);

        verify(budgetRepository, never()).findByIdWithLock(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void autoVoidStaleEvent_isNoOp_whenEventNoLongerExists() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.empty());

        // Must not throw — sweep should silently skip missing events
        service.autoVoidStaleEvent(event);

        verify(budgetRepository, never()).findByIdWithLock(any());
    }

    // -------------------------------------------------------------------------
    // confirmEvent — webhook dispatch verification
    // -------------------------------------------------------------------------

    @Test
    void confirmEvent_dispatchesSpendConfirmedWebhook() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));
        when(webhookPayloadBuilder.buildSpendConfirmedPayload(any(), any())).thenReturn(java.util.Map.of());

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedAmount(new BigDecimal("100.00"));

        service.confirmEvent(event.getId(), req, tenant);

        verify(webhookDispatcher).dispatch(
            eq(tenant.getId()),
            eq(WebhookEventType.SPEND_CONFIRMED),
            any());
    }

    @Test
    void confirmEvent_triggersAnomalyBaselineUpdate() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));
        when(webhookPayloadBuilder.buildSpendConfirmedPayload(any(), any())).thenReturn(java.util.Map.of());

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedAmount(new BigDecimal("75.00"));

        service.confirmEvent(event.getId(), req, tenant);

        verify(anomalyBaselineService).updateBaseline(eq(budget), eq(new BigDecimal("75.00")));
    }

    // -------------------------------------------------------------------------
    // failEvent — webhook dispatch verification
    // -------------------------------------------------------------------------

    @Test
    void failEvent_dispatchesSpendPaymentFailedWebhook() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));
        when(webhookPayloadBuilder.buildSpendPaymentFailedPayload(any(), any())).thenReturn(java.util.Map.of());

        var req = new com.figuard.api.dto.request.FailEventRequest();
        req.setReason("PAYMENT_DECLINED");

        service.failEvent(event.getId(), req, tenant);

        verify(webhookDispatcher).dispatch(
            eq(tenant.getId()),
            eq(WebhookEventType.SPEND_PAYMENT_FAILED),
            any());
    }

    // -------------------------------------------------------------------------
    // voidEvent — child event cascading
    // -------------------------------------------------------------------------

    @Test
    void voidEvent_withVoidChildEvents_voidsEligibleChildren() {
        SpendEvent child = new SpendEvent();
        child.setId(UUID.randomUUID());
        child.setDecision(SpendDecision.AUTHORIZED);
        child.setRequestedAmount(new BigDecimal("20.00"));

        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(eventRepository.findByParentEventId(event.getId())).thenReturn(List.of(child));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));
        when(webhookPayloadBuilder.buildSpendVoidedPayload(any(), any())).thenReturn(java.util.Map.of());

        VoidEventRequest req = new VoidEventRequest();
        req.setReason("USER_CANCELLED");
        req.setVoidChildEvents(true);

        service.voidEvent(event.getId(), req, tenant);

        // Child must have been voided
        assertThat(child.getDecision()).isEqualTo(SpendDecision.VOIDED);
        assertThat(child.getFailureReason()).isEqualTo("USER_CANCELLED");
    }

    @Test
    void voidEvent_withoutVoidChildEvents_leavesChildrenUntouched() {
        SpendEvent child = new SpendEvent();
        child.setId(UUID.randomUUID());
        child.setDecision(SpendDecision.AUTHORIZED);

        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));
        when(webhookPayloadBuilder.buildSpendVoidedPayload(any(), any())).thenReturn(java.util.Map.of());

        VoidEventRequest req = new VoidEventRequest();
        req.setReason("USER_CANCELLED");
        req.setVoidChildEvents(false);

        service.voidEvent(event.getId(), req, tenant);

        // Children not touched when voidChildEvents=false
        verify(eventRepository, never()).findByParentEventId(any());
        assertThat(child.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void confirmEvent_returns404_whenEventBelongsToOtherTenant() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        // Event belongs to 'tenant', request comes from 'otherTenant'
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedAmount(new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.confirmEvent(event.getId(), req, otherTenant))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }
}
