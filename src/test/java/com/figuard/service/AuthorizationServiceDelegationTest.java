package com.figuard.service;

import com.figuard.api.dto.request.AuthorizeSpendRequest;
import com.figuard.api.dto.response.AuthorizationResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.*;
import com.figuard.domain.enums.*;
import com.figuard.domain.repository.*;
import com.figuard.service.model.MatchResult;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the delegation token path in AuthorizationService:
 * - Token lookup fallback from budget → delegation token
 * - Delegate cap enforcement (DELEGATE_CAP_EXCEEDED)
 * - Fleet allocation still checked even when delegate cap passes
 * - Delegate allocation reserved on approve
 * - Categories without a delegate cap pass through to fleet allocation only
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceDelegationTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock BudgetAllocationRepository allocationRepository;
    @Mock SpendEventRepository spendEventRepository;
    @Mock CategoryMatchingService categoryMatchingService;
    @Mock SessionTokenService sessionTokenService;
    @Mock IntentScopeValidator intentScopeValidator;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;
    @Mock BudgetMapper budgetMapper;
    @Mock BudgetAnomalyBaselineRepository anomalyBaselineRepository;
    @Mock DelegatedTokenRepository delegatedTokenRepository;
    @Mock DelegatedTokenAllocationRepository delegatedTokenAllocationRepository;

    @InjectMocks AuthorizationService authorizationService;

    private Tenant tenant;
    private AgentBudget fleetBudget;
    private DelegatedToken delegatedToken;
    private DelegatedTokenAllocation delegateCap;
    private BudgetAllocation fleetAllocation;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        setField(tenant, "id", UUID.randomUUID());

        // Fleet budget with $50k refund allocation
        fleetBudget = new AgentBudget();
        setField(fleetBudget, "id", UUID.randomUUID());
        fleetBudget.setTenant(tenant);
        fleetBudget.setStatus(BudgetStatus.ACTIVE);
        fleetBudget.setTotalLimit(BigDecimal.valueOf(50000));
        fleetBudget.setQuantityReserved(BigDecimal.ZERO);
        fleetBudget.setQuantitySpent(BigDecimal.ZERO);
        fleetBudget.setExpiresAt(OffsetDateTime.now().plusHours(24));
        fleetBudget.setFirstAuthorizeDeadline(OffsetDateTime.now().plusHours(1));

        // Delegate token: $3k cap on "refund"
        delegatedToken = new DelegatedToken();
        setField(delegatedToken, "id", UUID.randomUUID());
        delegatedToken.setParentBudget(fleetBudget);
        delegatedToken.setTenant(tenant);
        delegatedToken.setStatus(DelegatedTokenStatus.ACTIVE);

        delegateCap = new DelegatedTokenAllocation();
        setField(delegateCap, "id", UUID.randomUUID());
        delegateCap.setDelegatedToken(delegatedToken);
        delegateCap.setCategory("refund");
        delegateCap.setTotalLimit(BigDecimal.valueOf(3000));
        delegateCap.setQuantitySpent(BigDecimal.ZERO);
        delegateCap.setQuantityReserved(BigDecimal.ZERO);

        // Fleet allocation: $50k refund
        fleetAllocation = new BudgetAllocation();
        setField(fleetAllocation, "id", UUID.randomUUID());
        fleetAllocation.setParentBudget(fleetBudget);
        fleetAllocation.setTenant(tenant);
        fleetAllocation.setCategory("refund");
        fleetAllocation.setAllowedCategories(new String[]{"refund"});
        fleetAllocation.setTotalLimit(BigDecimal.valueOf(50000));
        fleetAllocation.setQuantitySpent(BigDecimal.ZERO);
        fleetAllocation.setQuantityReserved(BigDecimal.ZERO);
        fleetAllocation.setStatus(AllocationStatus.ACTIVE);
        fleetAllocation.setEnforcementMode(EnforcementMode.CATEGORY_CONSTRAINED);

        when(sessionTokenService.hashToken(anyString())).thenReturn("token-hash");
        when(budgetMapper.toBudgetSnapshot(any())).thenReturn(mock(
            com.figuard.api.dto.response.BudgetSnapshot.class));
    }

    // -------------------------------------------------------------------------
    // Token resolution
    // -------------------------------------------------------------------------

    @Test
    void authorize_viaDirectBudgetToken_normalFlow() {
        // Direct budget token found — delegation path not taken
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any()))
            .thenReturn(Optional.of(fleetBudget));
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(fleetAllocation));
        when(categoryMatchingService.findMatch(any(), anyString(), any()))
            .thenReturn(new MatchResult.Match(fleetAllocation));
        when(allocationRepository.findByIdWithLock(any())).thenReturn(Optional.of(fleetAllocation));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        AuthorizeSpendRequest req = request(BigDecimal.valueOf(500), "refund");
        AuthorizationResponse resp = authorizationService.authorize("raw-token", req, tenant);

        assertThat(resp.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
        verify(delegatedTokenRepository, never()).findActiveBySessionTokenHash(any());
    }

    @Test
    void authorize_viaDelegationToken_fallsBackWhenNoBudgetFound() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any()))
            .thenReturn(Optional.empty());
        when(delegatedTokenRepository.findActiveBySessionTokenHash("token-hash"))
            .thenReturn(Optional.of(delegatedToken));
        when(budgetRepository.findByIdWithLock(fleetBudget.getId()))
            .thenReturn(Optional.of(fleetBudget));
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(fleetBudget.getId()))
            .thenReturn(List.of(fleetAllocation));
        when(categoryMatchingService.findMatch(any(), eq("refund"), any()))
            .thenReturn(new MatchResult.Match(fleetAllocation));
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "refund")).thenReturn(Optional.of(delegateCap));
        when(allocationRepository.findByIdWithLock(fleetAllocation.getId()))
            .thenReturn(Optional.of(fleetAllocation));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        AuthorizeSpendRequest req = request(BigDecimal.valueOf(500), "refund");
        AuthorizationResponse resp = authorizationService.authorize("raw-token", req, tenant);

        assertThat(resp.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void authorize_delegateToken_unknown_returns401() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any()))
            .thenReturn(Optional.empty());
        when(delegatedTokenRepository.findActiveBySessionTokenHash("token-hash"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizationService.authorize("bad-token",
            request(BigDecimal.TEN, "refund"), tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401");
    }

    // -------------------------------------------------------------------------
    // Delegate cap enforcement
    // -------------------------------------------------------------------------

    @Test
    void authorize_deniedWhenDelegateCapExceeded() {
        // Delegate cap: $3k, but $2,900 already reserved — only $100 left
        delegateCap.setQuantityReserved(BigDecimal.valueOf(2900));

        setupDelegateTokenPath();
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "refund")).thenReturn(Optional.of(delegateCap));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        // Request $500 — exceeds the $100 remaining on delegate cap
        AuthorizeSpendRequest req = request(BigDecimal.valueOf(500), "refund");
        AuthorizationResponse resp = authorizationService.authorize("raw-token", req, tenant);

        assertThat(resp.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(resp.getDenialReason()).isEqualTo(DenialCode.DELEGATE_CAP_EXCEEDED);
        // Fleet allocation lock should NOT be acquired when delegate cap already fails
        verify(allocationRepository, never()).findByIdWithLock(any());
    }

    @Test
    void authorize_deniedByFleetAllocation_evenWhenDelegateCapPasses() {
        // Fleet has $200 left (nearly exhausted), delegate cap has $3k
        fleetAllocation.setQuantityReserved(BigDecimal.valueOf(49800));

        setupDelegateTokenPath();
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "refund")).thenReturn(Optional.of(delegateCap));
        when(allocationRepository.findByIdWithLock(fleetAllocation.getId()))
            .thenReturn(Optional.of(fleetAllocation));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        // Request $500 — fleet has only $200 available
        AuthorizeSpendRequest req = request(BigDecimal.valueOf(500), "refund");
        AuthorizationResponse resp = authorizationService.authorize("raw-token", req, tenant);

        assertThat(resp.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(resp.getDenialReason()).isEqualTo(DenialCode.ALLOCATION_EXHAUSTED);
    }

    @Test
    void authorize_categoryWithoutDelegateCap_passesToFleetOnly() {
        // The delegate token has no cap for "email" category — only fleet limit applies
        setupDelegateTokenPath();
        // No cap found for "email"
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "email")).thenReturn(Optional.empty());

        BudgetAllocation emailAlloc = buildAllocation("email", 20000);
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(fleetBudget.getId()))
            .thenReturn(List.of(emailAlloc));
        when(categoryMatchingService.findMatch(any(), eq("email"), any()))
            .thenReturn(new MatchResult.Match(emailAlloc));
        when(allocationRepository.findByIdWithLock(emailAlloc.getId()))
            .thenReturn(Optional.of(emailAlloc));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        AuthorizeSpendRequest req = request(BigDecimal.valueOf(5), "email");
        AuthorizationResponse resp = authorizationService.authorize("raw-token", req, tenant);

        assertThat(resp.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void authorize_delegateCapApproved_reservesOnDelegateCap() {
        setupDelegateTokenPath();
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "refund")).thenReturn(Optional.of(delegateCap));
        when(allocationRepository.findByIdWithLock(fleetAllocation.getId()))
            .thenReturn(Optional.of(fleetAllocation));
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0); setField(e, "id", UUID.randomUUID()); return e;
        });

        AuthorizeSpendRequest req = request(BigDecimal.valueOf(500), "refund");
        authorizationService.authorize("raw-token", req, tenant);

        // Delegate allocation must have been saved with updated quantityReserved
        verify(delegatedTokenAllocationRepository).save(argThat(a ->
            a.getQuantityReserved().compareTo(BigDecimal.valueOf(500)) == 0));
    }

    @Test
    void authorize_delegateCapApproved_delegatedTokenIdSetOnSpendEvent() {
        setupDelegateTokenPath();
        when(delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
            delegatedToken.getId(), "refund")).thenReturn(Optional.of(delegateCap));
        when(allocationRepository.findByIdWithLock(fleetAllocation.getId()))
            .thenReturn(Optional.of(fleetAllocation));

        SpendEvent[] capturedEvent = new SpendEvent[1];
        when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0);
            setField(e, "id", UUID.randomUUID());
            capturedEvent[0] = e;
            return e;
        });

        authorizationService.authorize("raw-token", request(BigDecimal.valueOf(200), "refund"), tenant);

        assertThat(capturedEvent[0].getDelegatedTokenId()).isEqualTo(delegatedToken.getId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setupDelegateTokenPath() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any()))
            .thenReturn(Optional.empty());
        when(delegatedTokenRepository.findActiveBySessionTokenHash("token-hash"))
            .thenReturn(Optional.of(delegatedToken));
        when(budgetRepository.findByIdWithLock(fleetBudget.getId()))
            .thenReturn(Optional.of(fleetBudget));
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(fleetBudget.getId()))
            .thenReturn(List.of(fleetAllocation));
        when(categoryMatchingService.findMatch(any(), eq("refund"), any()))
            .thenReturn(new MatchResult.Match(fleetAllocation));
    }

    private BudgetAllocation buildAllocation(String category, int limit) {
        BudgetAllocation a = new BudgetAllocation();
        setField(a, "id", UUID.randomUUID());
        a.setParentBudget(fleetBudget);
        a.setTenant(tenant);
        a.setCategory(category);
        a.setAllowedCategories(new String[]{category});
        a.setTotalLimit(BigDecimal.valueOf(limit));
        a.setQuantitySpent(BigDecimal.ZERO);
        a.setQuantityReserved(BigDecimal.ZERO);
        a.setStatus(AllocationStatus.ACTIVE);
        a.setEnforcementMode(EnforcementMode.CATEGORY_CONSTRAINED);
        return a;
    }

    private AuthorizeSpendRequest request(BigDecimal qty, String category) {
        AuthorizeSpendRequest r = new AuthorizeSpendRequest();
        r.setAgentId("test-agent");
        r.setActionType("REFUND");
        r.setDescription("test");
        r.setRequestedQuantity(qty);
        r.setIdempotencyKey(UUID.randomUUID().toString());
        r.setClaimedCategory(category);
        return r;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            var f = findField(obj.getClass(), name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
