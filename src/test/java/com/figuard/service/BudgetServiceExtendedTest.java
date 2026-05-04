package com.figuard.service;

import com.figuard.api.dto.request.UpdateBudgetRequest;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        budget.setAmountSpent(new BigDecimal("100.00"));
        budget.setAmountReserved(BigDecimal.ZERO);
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
        // budget.amountSpent = 100.00; trying to set totalLimit = 90.00
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setTotalLimit(new BigDecimal("90.00"));

        assertThatThrownBy(() -> budgetService.updateBudget(budget.getId(), req, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("amountSpent");
    }

    @Test
    void updateBudget_allows_totalLimitEqualToAmountSpent() {
        // Exactly equal to amountSpent is allowed (budget is fully spent)
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        UpdateBudgetRequest req = new UpdateBudgetRequest();
        req.setTotalLimit(new BigDecimal("100.00")); // == amountSpent

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
        budget.setAmountReserved(new BigDecimal("200.00"));
        when(budgetRepository.findByIdWithLock(budget.getId())).thenReturn(Optional.of(budget));
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toResponse(any(AgentBudget.class))).thenReturn(null);

        budgetService.cancelBudget(budget.getId(), tenant);

        // Reserved funds must not be zeroed by cancel
        assertThat(budget.getAmountReserved()).isEqualByComparingTo("200.00");
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
}
