package com.figuard.service;

import com.figuard.api.dto.request.UpdateBudgetRequest;
import com.figuard.api.dto.request.ExtendBudgetRequest;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.exception.BudgetNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for updateBudget and cancelBudget — gaps in the original BudgetServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceExtendedTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock SessionTokenService sessionTokenService;
    @Mock BudgetMapper budgetMapper;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;

    @InjectMocks BudgetService budgetService;

    Tenant tenant;
    AgentBudget budget;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(budgetService, "maxExpiryHours", 24);
        ReflectionTestUtils.setField(budgetService, "firstAuthorizeDeadlineSeconds", 900);
        ReflectionTestUtils.setField(budgetService, "tokenRotationGraceSeconds", 60);

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        budget = new AgentBudget();
        budget.setId(UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setStatus(BudgetStatus.ACTIVE);
        budget.setTotalLimit(new BigDecimal("500.00"));
        budget.setQuantitySpent(new BigDecimal("100.00"));
        budget.setQuantityReserved(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // updateBudget — status transitions
    // -------------------------------------------------------------------------

    @Test
    void updateBudget_canPauseActiveBudget() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.PAUSED);

        budgetService.updateBudget(budget.getId(), req, tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.PAUSED);
    }

    @Test
    void updateBudget_canResumeFromPaused() {
        budget.setStatus(BudgetStatus.PAUSED);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.ACTIVE);

        budgetService.updateBudget(budget.getId(), req, tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
    }

    @Test
    void updateBudget_rejects_settingTerminalStatusViaApi() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        // EXHAUSTED is a system-set status and must not be settable via API
        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.EXHAUSTED);

        assertThatThrownBy(() -> budgetService.updateBudget(budget.getId(), req, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ACTIVE or PAUSED");
    }

    @Test
    void updateBudget_rejects_settingCancelledStatusViaApi() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.CANCELLED);

        assertThatThrownBy(() -> budgetService.updateBudget(budget.getId(), req, tenant))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateBudget_canIncreaseTotalLimit() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setTotalLimit(new BigDecimal("800.00"));

        budgetService.updateBudget(budget.getId(), req, tenant);

        assertThat(budget.getTotalLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void updateBudget_rejects_totalLimitBelowAmountSpent() {
        // budget.quantitySpent = 100.00; trying to set totalLimit = 90.00
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setTotalLimit(new BigDecimal("90.00"));

        assertThatThrownBy(() -> budgetService.updateBudget(budget.getId(), req, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("quantitySpent");
    }

    @Test
    void updateBudget_allows_totalLimitEqualToAmountSpent() {
        // Exactly equal to quantitySpent is allowed (budget is fully spent)
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setTotalLimit(new BigDecimal("100.00")); // == quantitySpent

        budgetService.updateBudget(budget.getId(), req, tenant);

        assertThat(budget.getTotalLimit()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateBudget_returns404_forUnknownId() {
        when(budgetRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetService.updateBudget(UUID.randomUUID(), new UpdateBudgetRequest(), tenant))
            .isInstanceOf(BudgetNotFoundException.class);
    }

    @Test
    void updateBudget_returns404_whenBudgetBelongsToOtherTenant() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.updateBudget(budget.getId(), new UpdateBudgetRequest(), otherTenant))
            .isInstanceOf(BudgetNotFoundException.class);
    }

    @Test
    void updateBudget_nullFields_areIgnored() {
        // Null fields in request must not overwrite existing values (PATCH semantics)
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        // All fields null — nothing should change
        UpdateBudgetRequest req = new UpdateBudgetRequest();
        budgetService.updateBudget(budget.getId(), req, tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(budget.getTotalLimit()).isEqualByComparingTo("500.00");
    }

    // -------------------------------------------------------------------------
    // cancelBudget
    // -------------------------------------------------------------------------

    @Test
    void cancelBudget_setsStatusAndCancellationTimestamp() {
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBudget(budget.getId(), tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
        assertThat(budget.getCancelledAt()).isNotNull();
        assertThat(budget.getCancelledAt()).isAfter(OffsetDateTime.now().minusSeconds(5));
    }

    @Test
    void cancelBudget_rejects_alreadyCancelledBudget() {
        budget.setStatus(BudgetStatus.CANCELLED);
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.cancelBudget(budget.getId(), tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("terminal state");
    }

    @Test
    void cancelBudget_rejects_expiredBudget() {
        budget.setStatus(BudgetStatus.EXPIRED);
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.cancelBudget(budget.getId(), tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("terminal state");
    }

    @Test
    void cancelBudget_rejects_exhaustedBudget() {
        budget.setStatus(BudgetStatus.EXHAUSTED);
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.cancelBudget(budget.getId(), tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("terminal state");
    }

    @Test
    void cancelBudget_preservesAmountReserved() {
        // Cancel does NOT release reservations — that is the caller's responsibility
        budget.setQuantityReserved(new BigDecimal("200.00"));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBudget(budget.getId(), tenant);

        // Reserved funds must not be zeroed by cancel
        assertThat(budget.getQuantityReserved()).isEqualByComparingTo("200.00");
    }

    @Test
    void cancelBudget_returns404_whenBudgetBelongsToOtherTenant() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.cancelBudget(budget.getId(), otherTenant))
            .isInstanceOf(BudgetNotFoundException.class);
    }

    @Test
    void cancelBudget_canCancelPausedBudget() {
        // PAUSED is not a terminal state — should be cancellable
        budget.setStatus(BudgetStatus.PAUSED);
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBudget(budget.getId(), tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // updateBudget — BUDGET_PAUSED webhook (Issue 7)
    // -------------------------------------------------------------------------

    @Test
    void updateBudget_firesBudgetPausedWebhook_whenTransitioningToPaused() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);
        when(webhookPayloadBuilder.buildBudgetPausedPayload(any(), eq("MANUAL"))).thenReturn(Map.of());

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.PAUSED);

        budgetService.updateBudget(budget.getId(), req, tenant);

        verify(webhookDispatcher).dispatch(eq(tenant.getId()), eq(WebhookEventType.BUDGET_PAUSED), any());
    }

    @Test
    void updateBudget_doesNotFireBudgetPausedWebhook_whenAlreadyPaused() {
        budget.setStatus(BudgetStatus.PAUSED); // already paused — no transition
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.PAUSED);

        budgetService.updateBudget(budget.getId(), req, tenant);

        verify(webhookDispatcher, never()).dispatch(any(), eq(WebhookEventType.BUDGET_PAUSED), any());
    }

    @Test
    void updateBudget_doesNotFireBudgetPausedWebhook_whenResumingFromPaused() {
        budget.setStatus(BudgetStatus.PAUSED);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setStatus(BudgetStatus.ACTIVE);

        budgetService.updateBudget(budget.getId(), req, tenant);

        verify(webhookDispatcher, never()).dispatch(any(), eq(WebhookEventType.BUDGET_PAUSED), any());
    }

    // -------------------------------------------------------------------------
    // cancelBatch (Issue 9)
    // -------------------------------------------------------------------------

    @Test
    void cancelBatch_cancelsEligibleBudgets() {
        AgentBudget b2 = new AgentBudget();
        b2.setId(UUID.randomUUID());
        b2.setTenant(tenant);
        b2.setStatus(BudgetStatus.ACTIVE);

        when(budgetRepository.findByTenantAndIdIn(eq(tenant), any()))
            .thenReturn(List.of(budget, b2));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBatch(List.of(budget.getId(), b2.getId()), tenant);

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
        assertThat(b2.getStatus()).isEqualTo(BudgetStatus.CANCELLED);
    }

    @Test
    void cancelBatch_skipsAlreadyTerminalBudgets() {
        budget.setStatus(BudgetStatus.EXPIRED);

        when(budgetRepository.findByTenantAndIdIn(eq(tenant), any()))
            .thenReturn(List.of(budget));
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBatch(List.of(budget.getId()), tenant);

        // Already terminal — must not call save
        verify(budgetRepository, never()).save(any());
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.EXPIRED);
    }

    @Test
    void cancelBatch_rejects_emptyList() {
        assertThatThrownBy(() -> budgetService.cancelBatch(List.of(), tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void cancelBatch_rejects_listExceeding100() {
        List<UUID> ids = java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> UUID.randomUUID())
            .toList();

        assertThatThrownBy(() -> budgetService.cancelBatch(ids, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("100");
    }

    // -------------------------------------------------------------------------
    // extendBudget (Issue 1b)
    // -------------------------------------------------------------------------

    @Test
    void extendBudget_updatesExpiresAt() {
        budget.setExpiresAt(OffsetDateTime.now().plusHours(1));
        OffsetDateTime newExpiry = OffsetDateTime.now().plusHours(3);

        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.extendBudget(budget.getId(), newExpiry, tenant);

        assertThat(budget.getExpiresAt()).isEqualTo(newExpiry);
    }

    @Test
    void extendBudget_rejects_whenNewExpiresAtIsBeforeCurrent() {
        budget.setExpiresAt(OffsetDateTime.now().plusHours(3));
        OffsetDateTime earlier = OffsetDateTime.now().plusHours(1);

        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.extendBudget(budget.getId(), earlier, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("later than the current expiresAt");
    }

    @Test
    void extendBudget_rejects_whenBudgetIsCancelled() {
        budget.setStatus(BudgetStatus.CANCELLED);
        budget.setExpiresAt(OffsetDateTime.now().plusHours(1));

        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.extendBudget(budget.getId(), OffsetDateTime.now().plusHours(2), tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CANCELLED");
    }

    @Test
    void extendBudget_rejects_whenNewExpiresAtExceeds24hCap() {
        budget.setExpiresAt(OffsetDateTime.now().plusHours(1));

        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.extendBudget(budget.getId(), OffsetDateTime.now().plusHours(25), tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("24");
    }

    @Test
    void extendBudget_returns404_forOtherTenant() {
        Tenant other = new Tenant();
        other.setId(UUID.randomUUID());
        budget.setExpiresAt(OffsetDateTime.now().plusHours(1));

        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));

        assertThatThrownBy(() -> budgetService.extendBudget(budget.getId(), OffsetDateTime.now().plusHours(2), other))
            .isInstanceOf(BudgetNotFoundException.class);
    }
}
