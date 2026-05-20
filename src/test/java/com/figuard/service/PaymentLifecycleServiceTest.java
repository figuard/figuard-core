package com.figuard.service;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
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
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentLifecycleServiceTest {

    @Mock SpendEventRepository eventRepository;
    @Mock AgentBudgetRepository budgetRepository;
    @Mock BudgetAllocationRepository allocationRepository;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;
    @Mock BudgetMapper budgetMapper;
    @Mock AnomalyBaselineService anomalyBaselineService;
    @org.mockito.Spy
    io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

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
        budget.setQuantityReserved(new BigDecimal("50.00"));
        budget.setQuantitySpent(BigDecimal.ZERO);

        event = new SpendEvent();
        event.setId(UUID.randomUUID());
        event.setTenant(tenant);
        event.setBudget(budget);
        event.setDecision(SpendDecision.AUTHORIZED);
        event.setRequestedQuantity(new BigDecimal("50.00"));
        event.setConfirmationTimeoutAt(OffsetDateTime.now().plusMinutes(5));
    }

    // -------------------------------------------------------------------------
    // Confirm
    // -------------------------------------------------------------------------

    @Test
    void confirm_movesReservedToSpent() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedQuantity(new BigDecimal("48.00"));
        req.setExternalTransactionId("pi_test_123");

        service.confirmEvent(event.getId(), req, tenant);

        assertThat(budget.getQuantityReserved()).isEqualByComparingTo("0.00");
        assertThat(budget.getQuantitySpent()).isEqualByComparingTo("48.00");
        assertThat(event.getDecision()).isEqualTo(SpendDecision.CONFIRMED);
        assertThat(event.getConfirmedQuantity()).isEqualByComparingTo("48.00");
        assertThat(event.getExternalTransactionId()).isEqualTo("pi_test_123");
    }

    @Test
    void confirm_rejectsIfAlreadyConfirmed() {
        event.setDecision(SpendDecision.CONFIRMED);
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedQuantity(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.confirmEvent(event.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CONFIRMED");
    }

    @Test
    void confirm_rejectsIfPastTimeout() {
        event.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(10));
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        ConfirmEventRequest req = new ConfirmEventRequest();
        req.setConfirmedQuantity(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.confirmEvent(event.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("AUTHORIZATION_EXPIRED");
    }

    // -------------------------------------------------------------------------
    // Fail
    // -------------------------------------------------------------------------

    @Test
    void fail_releasesReservation() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));

        FailEventRequest req = new FailEventRequest();
        req.setReason("PAYMENT_DECLINED");

        service.failEvent(event.getId(), req, tenant);

        assertThat(budget.getQuantityReserved()).isEqualByComparingTo("0.00");
        assertThat(budget.getQuantitySpent()).isEqualByComparingTo("0.00");
        assertThat(event.getDecision()).isEqualTo(SpendDecision.FAILED);
        assertThat(event.getFailureReason()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void fail_rejectsIfNotAuthorized() {
        event.setDecision(SpendDecision.CONFIRMED);
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        FailEventRequest req = new FailEventRequest();
        req.setReason("PAYMENT_DECLINED");

        assertThatThrownBy(() -> service.failEvent(event.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class);
    }

    // -------------------------------------------------------------------------
    // Void
    // -------------------------------------------------------------------------

    @Test
    void void_releasesReservation() {
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(eventRepository.save(any())).thenReturn(event);
        when(budgetMapper.toResponse(any(SpendEvent.class))).thenReturn(mock(SpendEventResponse.class));

        VoidEventRequest req = new VoidEventRequest();
        req.setReason("USER_CANCELLED");

        service.voidEvent(event.getId(), req, tenant);

        assertThat(budget.getQuantityReserved()).isEqualByComparingTo("0.00");
        assertThat(event.getDecision()).isEqualTo(SpendDecision.VOIDED);
    }

    @Test
    void void_rejectsIfExternalTransactionIdPresent() {
        event.setExternalTransactionId("pi_already_charged");
        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));

        VoidEventRequest req = new VoidEventRequest();
        req.setReason("USER_CANCELLED");

        assertThatThrownBy(() -> service.voidEvent(event.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("VOID_REQUIRES_REFUND");
    }

    @Test
    void void_rejectsIfAnyChildHasExternalTransaction() {
        SpendEvent child = new SpendEvent();
        child.setId(UUID.randomUUID());
        child.setDecision(SpendDecision.AUTHORIZED);
        child.setExternalTransactionId("pi_child_charged");

        when(eventRepository.findByIdWithLock(event.getId())).thenReturn(Optional.of(event));
        when(eventRepository.findByParentEventId(event.getId())).thenReturn(java.util.List.of(child));
        when(eventRepository.findByParentEventId(child.getId())).thenReturn(java.util.List.of());
        // Budget lock is acquired before descendant validation — must be stubbed
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        VoidEventRequest req = new VoidEventRequest();
        req.setReason("USER_CANCELLED");
        req.setVoidChildEvents(true);

        assertThatThrownBy(() -> service.voidEvent(event.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("VOID_REQUIRES_REFUND");
    }
}
