package com.figuard.service;

import com.figuard.api.dto.request.CreateDelegationTokenRequest;
import com.figuard.api.dto.response.DelegationTokenResponse;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.DelegatedToken;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.DelegatedTokenStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.DelegatedTokenRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelegationServiceTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock DelegatedTokenRepository delegatedTokenRepository;
    @Mock SessionTokenService sessionTokenService;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;

    @InjectMocks DelegationService delegationService;

    private Tenant tenant;
    private AgentBudget budget;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        setField(tenant, "id", UUID.randomUUID());

        budget = new AgentBudget();
        setField(budget, "id", UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setStatus(BudgetStatus.ACTIVE);
        budget.setTotalLimit(BigDecimal.valueOf(50000));
    }

    // -------------------------------------------------------------------------
    // createToken
    // -------------------------------------------------------------------------

    @Test
    void createToken_returnsRawTokenOnce() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(sessionTokenService.generateToken()).thenReturn("st_TESTTOKEN12345678901234567890AB");
        when(sessionTokenService.hashToken(any())).thenReturn("hashhash");
        when(sessionTokenService.extractPrefix(any())).thenReturn("st_TESTTOKEN1");
        when(delegatedTokenRepository.save(any())).thenAnswer(inv -> {
            DelegatedToken t = inv.getArgument(0);
            setField(t, "id", UUID.randomUUID());
            setField(t, "createdAt", OffsetDateTime.now());
            return t;
        });

        CreateDelegationTokenRequest req = request("order-123",
            List.of(cap("refund", BigDecimal.valueOf(3000))));

        DelegationTokenResponse resp = delegationService.createToken(budget.getId(), req, tenant);

        assertThat(resp.getSessionToken()).isEqualTo("st_TESTTOKEN12345678901234567890AB");
        assertThat(resp.getLabel()).isEqualTo("order-123");
        assertThat(resp.getCaps()).hasSize(1);
        assertThat(resp.getCaps().get(0).getCategory()).isEqualTo("refund");
        assertThat(resp.getCaps().get(0).getTotalLimit()).isEqualByComparingTo("3000");
    }

    @Test
    void createToken_normalizesCategoryToLowercase() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(sessionTokenService.generateToken()).thenReturn("st_TOKEN");
        when(sessionTokenService.hashToken(any())).thenReturn("hash");
        when(sessionTokenService.extractPrefix(any())).thenReturn("st_TOKEN");
        when(delegatedTokenRepository.save(any())).thenAnswer(inv -> {
            DelegatedToken t = inv.getArgument(0);
            setField(t, "id", UUID.randomUUID());
            setField(t, "createdAt", OffsetDateTime.now());
            return t;
        });

        CreateDelegationTokenRequest req = request("order-abc",
            List.of(cap("REFUND", BigDecimal.valueOf(1000))));

        DelegationTokenResponse resp = delegationService.createToken(budget.getId(), req, tenant);
        assertThat(resp.getCaps().get(0).getCategory()).isEqualTo("refund");
    }

    @Test
    void createToken_label_idempotency_returnsExistingToken() {
        DelegatedToken existing = activeToken();
        existing.setLabel("order-123");

        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(delegatedTokenRepository.findActiveByBudgetIdAndLabel(budget.getId(), "order-123"))
            .thenReturn(Optional.of(existing));

        CreateDelegationTokenRequest req = request("order-123",
            List.of(cap("refund", BigDecimal.valueOf(3000))));

        DelegationTokenResponse resp = delegationService.createToken(budget.getId(), req, tenant);

        // Returns existing token — no new token created, no session token re-issued
        assertThat(resp.getId()).isEqualTo(existing.getId());
        assertThat(resp.getSessionToken()).isNull();
        verify(delegatedTokenRepository, never()).save(any());
        verify(sessionTokenService, never()).generateToken();
    }

    @Test
    void createToken_null_label_skips_idempotency_check() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        when(sessionTokenService.generateToken()).thenReturn("st_NOLAB");
        when(sessionTokenService.hashToken(any())).thenReturn("hash");
        when(sessionTokenService.extractPrefix(any())).thenReturn("st_NOLAB");
        when(delegatedTokenRepository.save(any())).thenAnswer(inv -> {
            DelegatedToken t = inv.getArgument(0);
            setField(t, "id", UUID.randomUUID());
            setField(t, "createdAt", OffsetDateTime.now());
            return t;
        });

        CreateDelegationTokenRequest req = request(null, List.of(cap("refund", BigDecimal.TEN)));

        delegationService.createToken(budget.getId(), req, tenant);

        // No label → no idempotency query
        verify(delegatedTokenRepository, never()).findActiveByBudgetIdAndLabel(any(), any());
        verify(delegatedTokenRepository).save(any());
    }

    @Test
    void createToken_rejects_cancelledBudget() {
        budget.setStatus(BudgetStatus.CANCELLED);
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        CreateDelegationTokenRequest req = request("lbl", List.of(cap("refund", BigDecimal.TEN)));

        assertThatThrownBy(() -> delegationService.createToken(budget.getId(), req, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CANCELLED");
    }

    @Test
    void createToken_rejects_wrongTenant() {
        Tenant other = new Tenant();
        setField(other, "id", UUID.randomUUID());
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        CreateDelegationTokenRequest req = request("lbl", List.of(cap("refund", BigDecimal.TEN)));

        assertThatThrownBy(() -> delegationService.createToken(budget.getId(), req, other))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    // -------------------------------------------------------------------------
    // revokeToken
    // -------------------------------------------------------------------------

    @Test
    void revokeToken_setsRevokedStatus_andFiresWebhook() {
        DelegatedToken token = activeToken();
        when(delegatedTokenRepository.findByIdWithLock(token.getId())).thenReturn(Optional.of(token));
        when(delegatedTokenRepository.save(any())).thenReturn(token);
        when(webhookPayloadBuilder.buildDelegationTokenRevokedPayload(any())).thenReturn(java.util.Map.of());

        DelegationTokenResponse resp = delegationService.revokeToken(token.getId(), tenant);

        assertThat(resp.getStatus()).isEqualTo("REVOKED");
        verify(webhookDispatcher).dispatch(eq(tenant.getId()),
            eq(WebhookEventType.DELEGATION_TOKEN_REVOKED), any());
    }

    @Test
    void revokeToken_isIdempotent_whenAlreadyRevoked() {
        DelegatedToken token = activeToken();
        token.setStatus(DelegatedTokenStatus.REVOKED);
        when(delegatedTokenRepository.findByIdWithLock(token.getId())).thenReturn(Optional.of(token));

        DelegationTokenResponse resp = delegationService.revokeToken(token.getId(), tenant);

        assertThat(resp.getStatus()).isEqualTo("REVOKED");
        verify(delegatedTokenRepository, never()).save(any());
        verify(webhookDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void revokeToken_rejects_wrongTenant() {
        Tenant other = new Tenant();
        setField(other, "id", UUID.randomUUID());
        DelegatedToken token = activeToken();
        when(delegatedTokenRepository.findByIdWithLock(token.getId())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> delegationService.revokeToken(token.getId(), other))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    // -------------------------------------------------------------------------
    // getToken / listTokens
    // -------------------------------------------------------------------------

    @Test
    void getToken_doesNotReturnRawToken() {
        DelegatedToken token = activeToken();
        setField(token, "createdAt", OffsetDateTime.now());
        when(delegatedTokenRepository.findById(token.getId())).thenReturn(Optional.of(token));

        DelegationTokenResponse resp = delegationService.getToken(token.getId(), tenant);

        assertThat(resp.getSessionToken()).isNull();
        assertThat(resp.getSessionTokenPrefix()).isNotNull();
    }

    @Test
    void listTokens_returnsAllForBudget() {
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));
        DelegatedToken t1 = activeToken();
        DelegatedToken t2 = activeToken();
        setField(t1, "createdAt", OffsetDateTime.now());
        setField(t2, "createdAt", OffsetDateTime.now());
        when(delegatedTokenRepository.findByParentBudgetId(budget.getId())).thenReturn(List.of(t1, t2));

        List<DelegationTokenResponse> tokens = delegationService.listTokens(budget.getId(), tenant);

        assertThat(tokens).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DelegatedToken activeToken() {
        DelegatedToken t = new DelegatedToken();
        setField(t, "id", UUID.randomUUID());
        t.setParentBudget(budget);
        t.setTenant(tenant);
        t.setLabel("test-token");
        t.setSessionTokenHash("hash");
        t.setSessionTokenPrefix("st_prefix");
        t.setStatus(DelegatedTokenStatus.ACTIVE);
        t.setCaps(List.of());
        return t;
    }

    private CreateDelegationTokenRequest request(String label,
            List<CreateDelegationTokenRequest.DelegationCapRequest> caps) {
        CreateDelegationTokenRequest r = new CreateDelegationTokenRequest();
        r.setLabel(label);
        r.setCaps(caps);
        return r;
    }

    private CreateDelegationTokenRequest.DelegationCapRequest cap(String category, BigDecimal limit) {
        CreateDelegationTokenRequest.DelegationCapRequest c =
            new CreateDelegationTokenRequest.DelegationCapRequest();
        c.setCategory(category);
        c.setLimit(limit);
        return c;
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
