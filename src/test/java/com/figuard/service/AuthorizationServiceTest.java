package com.figuard.service;

import com.figuard.api.dto.request.AuthorizeSpendRequest;
import com.figuard.api.dto.response.AuthorizationResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.AllocationStatus;
import org.mockito.Spy;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.EnforcementMode;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.service.model.MatchResult;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock BudgetAllocationRepository allocationRepository;
    @Mock SpendEventRepository spendEventRepository;
    @Mock CategoryMatchingService categoryMatchingService;
    @Mock SessionTokenService sessionTokenService;
    @Spy IntentScopeValidator intentScopeValidator;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;
    @Mock BudgetMapper budgetMapper;
    @Mock BudgetAnomalyBaselineRepository anomalyBaselineRepository;

    @InjectMocks AuthorizationService service;

    private Tenant tenant;
    private AgentBudget budget;
    private BudgetAllocation flightAllocation;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "confirmationTimeoutSeconds", 300);
        ReflectionTestUtils.setField(service, "expiryGraceSeconds", 60);

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Test Tenant");

        budget = new AgentBudget();
        budget.setId(UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setStatus(BudgetStatus.ACTIVE);
        budget.setTotalLimit(new BigDecimal("500.00"));
        budget.setQuantitySpent(BigDecimal.ZERO);
        budget.setQuantityReserved(BigDecimal.ZERO);
        budget.setCurrency("USD");
        budget.setExpiresAt(OffsetDateTime.now().plusHours(2));

        flightAllocation = new BudgetAllocation();
        flightAllocation.setId(UUID.randomUUID());
        flightAllocation.setCategory("flight");
        flightAllocation.setAllowedCategories(new String[]{"flight"});
        flightAllocation.setEnforcementMode(EnforcementMode.CATEGORY_CONSTRAINED);
        flightAllocation.setTotalLimit(new BigDecimal("300.00"));
        flightAllocation.setQuantitySpent(BigDecimal.ZERO);
        flightAllocation.setQuantityReserved(BigDecimal.ZERO);
        flightAllocation.setStatus(AllocationStatus.ACTIVE);

        // Default mock: sessionTokenService.hashToken() returns a fixed hash
        lenient().when(sessionTokenService.hashToken(anyString())).thenReturn("hashed_token");

        // Default mock: spendEventRepository.save() returns the event passed to it
        // lenient — not every test reaches the save call (e.g. token-not-found, idempotency hit)
        lenient().when(spendEventRepository.save(any())).thenAnswer(inv -> {
            SpendEvent e = inv.getArgument(0);
            if (e.getId() == null) {
                ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            }
            return e;
        });

        // Default mock: budgetMapper snapshots — lenient because not all paths reach snapshot building
        lenient().when(budgetMapper.toBudgetSnapshot(any())).thenReturn(
            com.figuard.api.dto.response.BudgetSnapshot.builder()
                .totalLimit(budget.getTotalLimit())
                .quantitySpent(budget.getQuantitySpent())
                .quantityReserved(budget.getQuantityReserved())
                .availableQuantity(budget.availableQuantity())
                .status(budget.getStatus())
                .build());
    }

    // -------------------------------------------------------------------------

    @Test
    void authorize_denies_whenTokenNotFound() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorize("bad_token", validRequest(), tenant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_SESSION_TOKEN");
    }

    @Test
    void authorize_denies_whenBudgetExpired() {
        budget.setExpiresAt(OffsetDateTime.now().minusHours(2)); // well past grace window
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        // allocationRepository NOT stubbed — expiry check fires before allocations are loaded

        AuthorizationResponse response = service.authorize("st_token", validRequest(), tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.BUDGET_EXPIRED);
    }

    @Test
    void authorize_denies_whenMissingClaimedCategory() {
        // MISSING_CLAIMED_CATEGORY is now a structured DENIED, not a 400 exception.
        // LLMs need a parseable decision — a raw 400 gives them nothing to reason about.
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), isNull(), any()))
            .thenReturn(new com.figuard.service.model.MatchResult.MissingCategory());

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory(null);

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.MISSING_CLAIMED_CATEGORY);
    }

    @Test
    void authorize_denies_whenNoMatchingAllocation() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.NoMatch());

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("car_rental");

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.NO_MATCHING_ALLOCATION);
        assertThat(response.getDenialMessage()).contains("car_rental");
    }

    @Test
    void authorize_denies_whenForbiddenItemType() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.Forbidden(flightAllocation, "gift_card"));
        when(budgetMapper.toAllocationSnapshot(any())).thenReturn(null);

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("flight");
        req.setClaimedItemType("gift_card");

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.FORBIDDEN_ITEM_TYPE);
        assertThat(response.getDenialMessage()).contains("gift_card");
    }

    @Test
    void authorize_denies_whenAllocationExhausted() {
        flightAllocation.setQuantitySpent(new BigDecimal("300.00")); // fully spent

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.Match(flightAllocation));
        when(allocationRepository.findByIdWithLock(any()))
            .thenReturn(Optional.of(flightAllocation));
        when(budgetMapper.toAllocationSnapshot(any())).thenReturn(null);

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("flight");

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.ALLOCATION_EXHAUSTED);
        assertThat(response.getDenialMessage()).contains("flight");
    }

    @Test
    void authorize_returnsAuthorized_andReservesAmount() {
        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.Match(flightAllocation));
        when(allocationRepository.findByIdWithLock(any()))
            .thenReturn(Optional.of(flightAllocation));
        when(allocationRepository.save(any())).thenReturn(flightAllocation);
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toAllocationSnapshot(any())).thenReturn(null);

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("flight");

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
        assertThat(response.getApprovedQuantity()).isEqualByComparingTo("100.00");
        assertThat(response.getAuthorizedAt()).isNotNull();

        // Verify reservation was written on both allocation and budget
        ArgumentCaptor<BudgetAllocation> allocCaptor = ArgumentCaptor.forClass(BudgetAllocation.class);
        verify(allocationRepository).save(allocCaptor.capture());
        assertThat(allocCaptor.getValue().getQuantityReserved())
            .isEqualByComparingTo("100.00");

        ArgumentCaptor<AgentBudget> budgetCaptor = ArgumentCaptor.forClass(AgentBudget.class);
        verify(budgetRepository).save(budgetCaptor.capture());
        assertThat(budgetCaptor.getValue().getQuantityReserved())
            .isEqualByComparingTo("100.00");
    }

    @Test
    void authorize_returnsCachedDecision_onDuplicateIdempotencyKey() {
        SpendEvent existing = new SpendEvent();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setDecision(SpendDecision.AUTHORIZED);
        existing.setRequestedQuantity(new BigDecimal("100.00"));
        existing.setBudget(budget);

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.of(existing));

        AuthorizationResponse response = service.authorize("st_token", validRequest(), tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
        // No new SpendEvent should be written
        verify(spendEventRepository, never()).save(any());
    }

    @Test
    void authorize_denies_whenRequestedAmountExceedsMaxTransactionAmount() {
        budget.setMaxTransactionQuantity(new BigDecimal("50.00")); // cap at $50 per transaction

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());

        AuthorizeSpendRequest req = validRequest();
        req.setRequestedQuantity(new BigDecimal("75.00")); // exceeds the $50 cap

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.EXCEEDS_QUANTITY_LIMIT);
        assertThat(response.getDenialMessage()).contains("75");
        assertThat(response.getDenialMessage()).contains("50");
        // Allocations should not be loaded — check is applied before that step
        verify(allocationRepository, never()).findByParentBudgetIdOrderByCreatedAtAsc(any());
    }

    @Test
    void authorize_approves_whenRequestedAmountEqualsMaxTransactionAmount() {
        budget.setMaxTransactionQuantity(new BigDecimal("100.00")); // cap exactly at request amount

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of());
        when(budgetRepository.save(any())).thenReturn(budget);

        AuthorizeSpendRequest req = validRequest();
        req.setRequestedQuantity(new BigDecimal("100.00")); // exactly at cap — should pass

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void authorize_ignoresMaxTransactionAmountCheck_whenNotSet() {
        // maxTransactionAmount is null — no cap applied, large amount should pass
        budget.setMaxTransactionQuantity(null);

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of());
        when(budgetRepository.save(any())).thenReturn(budget);

        AuthorizeSpendRequest req = validRequest();
        req.setRequestedQuantity(new BigDecimal("400.00")); // large but within totalLimit

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void authorize_setsParentEvent_whenParentEventIdProvided() {
        SpendEvent parent = new SpendEvent();
        UUID parentId = UUID.randomUUID();
        ReflectionTestUtils.setField(parent, "id", parentId);
        parent.setBudget(budget);

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(spendEventRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.Match(flightAllocation));
        when(allocationRepository.findByIdWithLock(any())).thenReturn(Optional.of(flightAllocation));
        when(allocationRepository.save(any())).thenReturn(flightAllocation);
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toAllocationSnapshot(any())).thenReturn(null);

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("flight");
        req.setParentEventId(parentId);

        service.authorize("st_token", req, tenant);

        ArgumentCaptor<SpendEvent> eventCaptor = ArgumentCaptor.forClass(SpendEvent.class);
        verify(spendEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getParentEvent()).isNotNull();
        assertThat(eventCaptor.getValue().getParentEvent().getId()).isEqualTo(parentId);
    }

    @Test
    void authorize_denies_currencyMismatch() {
        budget.setCurrency("USD");

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());

        AuthorizeSpendRequest req = validRequest();
        req.setCurrency("EUR"); // budget is USD

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.CURRENCY_MISMATCH);
        assertThat(response.getDenialMessage()).contains("EUR");
        assertThat(response.getDenialMessage()).contains("USD");
        // Should stop before loading allocations
        verify(allocationRepository, never()).findByParentBudgetIdOrderByCreatedAtAsc(any());
    }

    @Test
    void authorize_denies_intentScopeViolation_onFlatBudget_noContext() {
        budget.setIntentTags(new String[]{"travel", "flight"});

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of()); // flat — no allocations

        AuthorizeSpendRequest req = validRequest();
        req.setIntentContext(null); // no context provided against a tagged budget

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.INTENT_SCOPE_VIOLATION);
    }

    @Test
    void authorize_denies_intentScopeViolation_onFlatBudget_noOverlap() {
        budget.setIntentTags(new String[]{"travel", "flight"});

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of());

        AuthorizeSpendRequest req = validRequest();
        req.setIntentContext("book hotel room for conference"); // no "travel" or "flight" match

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.DENIED);
        assertThat(response.getDenialReason()).isEqualTo(DenialCode.INTENT_SCOPE_VIOLATION);
        assertThat(response.getDenialMessage()).contains("hotel room for conference");
    }

    @Test
    void authorize_approves_whenIntentMatches_onFlatBudget() {
        budget.setIntentTags(new String[]{"travel", "flight"});

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of());
        when(budgetRepository.save(any())).thenReturn(budget);

        AuthorizeSpendRequest req = validRequest();
        req.setIntentContext("purchase flight ticket to NYC"); // "flight" matches

        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void authorize_skipsIntentCheck_whenBudgetHasAllocations() {
        // Budget has intentTags BUT also has allocations — intent check must NOT run on allocated path.
        // The agent uses claimedCategory for enforcement, not intentContext.
        budget.setIntentTags(new String[]{"travel", "flight"});

        when(budgetRepository.findBySessionTokenHashOrPrevious(anyString(), any())).thenReturn(Optional.of(budget));
        when(spendEventRepository.findByBudgetIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of(flightAllocation));
        when(categoryMatchingService.findMatch(any(), any(), any()))
            .thenReturn(new MatchResult.Match(flightAllocation));
        when(allocationRepository.findByIdWithLock(any())).thenReturn(Optional.of(flightAllocation));
        when(allocationRepository.save(any())).thenReturn(flightAllocation);
        when(budgetRepository.save(any())).thenReturn(budget);
        when(budgetMapper.toAllocationSnapshot(any())).thenReturn(null);

        AuthorizeSpendRequest req = validRequest();
        req.setClaimedCategory("flight");
        req.setIntentContext(null); // no context — would fail intent check on flat path

        // Must still AUTHORIZE because intent check doesn't run on allocated budgets
        AuthorizationResponse response = service.authorize("st_token", req, tenant);

        assertThat(response.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    // -------------------------------------------------------------------------

    private AuthorizeSpendRequest validRequest() {
        AuthorizeSpendRequest req = new AuthorizeSpendRequest();
        req.setAgentId("agent_001");
        req.setActionType("PURCHASE");
        req.setDescription("Flight booking to NYC");
        req.setRequestedQuantity(new BigDecimal("100.00"));
        req.setCurrency("USD");
        req.setIdempotencyKey(UUID.randomUUID().toString());
        return req;
    }
}
