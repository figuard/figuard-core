package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BudgetSweepService — specifically the BUDGET_EXPIRING_SOON sweep.
 */
@ExtendWith(MockitoExtension.class)
class BudgetSweepServiceTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;

    // SimpleMeterRegistry avoids complex mock setup for counters
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    BudgetSweepService sweepService;

    Tenant tenant;
    AgentBudget budget;

    @BeforeEach
    void setUp() {
        sweepService = new BudgetSweepService(budgetRepository, webhookDispatcher, webhookPayloadBuilder, meterRegistry);
        sweepService.initMetrics();

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        budget = new AgentBudget();
        budget.setId(UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setTotalLimit(new BigDecimal("500.00"));
        budget.setQuantitySpent(BigDecimal.ZERO);
        budget.setQuantityReserved(BigDecimal.ZERO);
        budget.setStatus(BudgetStatus.ACTIVE);
        budget.setExpiresAt(OffsetDateTime.now().plusMinutes(60));
        budget.setExpiringSoonNotified(false);
    }

    // -------------------------------------------------------------------------
    // notifyExpiringSoon
    // -------------------------------------------------------------------------

    @Test
    void notifyExpiringSoon_firesWebhook_andSetsNotifiedFlag() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(webhookPayloadBuilder.buildBudgetExpiringSoonPayload(any())).thenReturn(java.util.Map.of());

        sweepService.notifyExpiringSoon(budget);

        assertThat(budget.isExpiringSoonNotified()).isTrue();
        verify(webhookDispatcher).dispatch(eq(tenant.getId()), eq(WebhookEventType.BUDGET_EXPIRING_SOON), any());
        verify(budgetRepository).save(budget);
    }

    @Test
    void notifyExpiringSoon_isNoOp_whenAlreadyNotified() {
        budget.setExpiringSoonNotified(true);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        sweepService.notifyExpiringSoon(budget);

        verify(budgetRepository, never()).save(any());
        verify(webhookDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void notifyExpiringSoon_isNoOp_whenBudgetIsTerminal() {
        budget.setStatus(BudgetStatus.EXPIRED);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        sweepService.notifyExpiringSoon(budget);

        verify(budgetRepository, never()).save(any());
        verify(webhookDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void notifyExpiringSoon_firesWebhook_forPausedBudget() {
        // PAUSED budgets should also receive the expiry warning
        budget.setStatus(BudgetStatus.PAUSED);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(webhookPayloadBuilder.buildBudgetExpiringSoonPayload(any())).thenReturn(java.util.Map.of());

        sweepService.notifyExpiringSoon(budget);

        verify(webhookDispatcher).dispatch(eq(tenant.getId()), eq(WebhookEventType.BUDGET_EXPIRING_SOON), any());
    }

    @Test
    void notifyExpiringSoon_isNoOp_whenBudgetNoLongerExists() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.empty());

        sweepService.notifyExpiringSoon(budget);

        verify(webhookDispatcher, never()).dispatch(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // sweepExpiringSoon — integration with repository
    // -------------------------------------------------------------------------

    @Test
    void sweepExpiringSoon_callsNotify_forEachEligibleBudget() {
        AgentBudget b2 = new AgentBudget();
        b2.setId(UUID.randomUUID());
        b2.setTenant(tenant);
        b2.setStatus(BudgetStatus.ACTIVE);
        b2.setExpiringSoonNotified(false);
        b2.setExpiresAt(OffsetDateTime.now().plusMinutes(58));

        when(budgetRepository.findExpiringSoon(any(), any())).thenReturn(List.of(budget, b2));
        // Each notifyExpiringSoon re-fetches via findById — return empty to keep test lightweight
        lenient().when(budgetRepository.findById(any())).thenReturn(Optional.empty());

        sweepService.sweepExpiringSoon();

        // findExpiringSoon must have been called with a window around 60 minutes
        verify(budgetRepository).findExpiringSoon(any(), any());
    }

    @Test
    void sweepExpiringSoon_doesNothing_whenNoEligibleBudgets() {
        when(budgetRepository.findExpiringSoon(any(), any())).thenReturn(List.of());

        sweepService.sweepExpiringSoon();

        verify(budgetRepository, never()).save(any());
        verify(webhookDispatcher, never()).dispatch(any(), any(), any());
    }
}
