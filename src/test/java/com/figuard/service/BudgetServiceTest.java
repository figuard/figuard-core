package com.figuard.service;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.response.BudgetResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.repository.AgentBudgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock SessionTokenService sessionTokenService;
    @Mock BudgetMapper budgetMapper;
    @Mock WebhookDispatcher webhookDispatcher;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;

    @InjectMocks BudgetService budgetService;

    private Tenant tenant;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(budgetService, "maxExpiryHours", 24);
        ReflectionTestUtils.setField(budgetService, "firstAuthorizeDeadlineSeconds", 900);

        tenant = new Tenant();

        // Default: no existing budget for any externalReference
        lenient().when(budgetRepository.findByTenantAndExternalReferenceAndStatusIn(
            any(), any(), any())).thenReturn(Optional.empty());
    }

    // -------------------------------------------------------------------------

    @Test
    void createBudget_requiresExpiresAt() {
        CreateBudgetRequest request = validRequest();
        request.setExpiresAt(null);

        assertThatThrownBy(() -> budgetService.createBudget(request, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expiresAt is required");
    }

    @Test
    void createBudget_rejectsExpiryBeyond24Hours() {
        CreateBudgetRequest request = validRequest();
        request.setExpiresAt(OffsetDateTime.now().plusHours(25));  // 1 hour over limit

        assertThatThrownBy(() -> budgetService.createBudget(request, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("24 hours");
    }

    @Test
    void createBudget_rejectsAllocationSumMismatch() {
        CreateBudgetRequest request = validRequest();
        request.setTotalLimit(new BigDecimal("100.00"));
        request.setAllocations(List.of(
            allocation("flight", new BigDecimal("60.00")),
            allocation("hotel",  new BigDecimal("30.00"))   // sum = 90, not 100
        ));

        assertThatThrownBy(() -> budgetService.createBudget(request, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Allocation limits sum");
    }

    @Test
    void createBudget_rejectsDuplicateAllocationCategory() {
        CreateBudgetRequest request = validRequest();
        request.setTotalLimit(new BigDecimal("100.00"));
        request.setAllocations(List.of(
            allocation("flight", new BigDecimal("50.00")),
            allocation("flight", new BigDecimal("50.00"))   // duplicate category
        ));

        assertThatThrownBy(() -> budgetService.createBudget(request, tenant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate allocation category");
    }

    @Test
    void createBudget_sessionTokenNeverStoredRaw() {
        String rawToken = "st_AbCdEfGhIjKlMnOpQrStUvWxYz012345";
        String tokenHash = "hashedvalue123";

        CreateBudgetRequest request = validRequest();

        AgentBudget mockBudget = new AgentBudget();
        mockBudget.setSessionTokenHash(tokenHash);
        mockBudget.setSessionTokenPrefix("st_AbCdEfGh");

        when(sessionTokenService.generateToken()).thenReturn(rawToken);
        when(sessionTokenService.hashToken(rawToken)).thenReturn(tokenHash);
        when(sessionTokenService.extractPrefix(rawToken)).thenReturn(rawToken.substring(0, 12));
        when(budgetMapper.toEntity(any(CreateBudgetRequest.class), any(Tenant.class)))
            .thenReturn(mockBudget);
        when(budgetRepository.save(any())).thenReturn(mockBudget);
        when(budgetMapper.toResponse(any(AgentBudget.class), eq(rawToken)))
            .thenReturn(BudgetResponse.builder()
                .sessionToken(rawToken)
                .sessionTokenPrefix(rawToken.substring(0, 12))
                .build());

        BudgetService.CreateBudgetResult result = budgetService.createBudget(request, tenant);
        BudgetResponse response = result.budget();
        assertThat(result.created()).isTrue();

        // Capture what was saved — verify raw token is NOT in any persisted field
        ArgumentCaptor<AgentBudget> captor = ArgumentCaptor.forClass(AgentBudget.class);
        verify(budgetRepository).save(captor.capture());
        AgentBudget saved = captor.getValue();

        assertThat(saved.getSessionTokenHash()).isEqualTo(tokenHash);
        assertThat(saved.getSessionTokenHash()).isNotEqualTo(rawToken);

        // Raw token IS in the response (returned once) but not in persisted entity
        assertThat(response.getSessionToken()).isEqualTo(rawToken);
    }

    @Test
    void createBudget_returnsExisting_whenExternalReferenceMatchesPayload() {
        AgentBudget existing = new AgentBudget();
        existing.setTotalLimit(new BigDecimal("500.00"));
        existing.setCurrency("USD");
        existing.setAllocations(List.of());

        CreateBudgetRequest request = validRequest();
        request.setExternalReference("orchestrator-run-42");

        BudgetResponse existingResponse = BudgetResponse.builder().build();
        when(budgetRepository.findByTenantAndExternalReferenceAndStatusIn(
            any(), eq("orchestrator-run-42"), any()))
            .thenReturn(Optional.of(existing));
        when(budgetMapper.toResponse(existing)).thenReturn(existingResponse);

        BudgetService.CreateBudgetResult result = budgetService.createBudget(request, tenant);

        assertThat(result.created()).isFalse();
        assertThat(result.budget()).isSameAs(existingResponse);
        // No new budget should be saved
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void createBudget_throws409_whenExternalReferenceConflictsWithDifferentPayload() {
        AgentBudget existing = new AgentBudget();
        existing.setTotalLimit(new BigDecimal("999.00")); // different totalLimit
        existing.setCurrency("USD");
        existing.setAllocations(List.of());

        CreateBudgetRequest request = validRequest(); // totalLimit = 500.00
        request.setExternalReference("orchestrator-run-42");

        when(budgetRepository.findByTenantAndExternalReferenceAndStatusIn(
            any(), eq("orchestrator-run-42"), any()))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> budgetService.createBudget(request, tenant))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("orchestrator-run-42");
    }

    @Test
    void createBudget_createsNew_whenNoExternalReference() {
        CreateBudgetRequest request = validRequest();
        // no externalReference set

        AgentBudget mockBudget = new AgentBudget();
        mockBudget.setSessionTokenPrefix("st_prefix");
        when(sessionTokenService.generateToken()).thenReturn("st_token");
        when(sessionTokenService.hashToken(any())).thenReturn("hashed");
        when(sessionTokenService.extractPrefix(any())).thenReturn("st_prefix");
        when(budgetMapper.toEntity(any(CreateBudgetRequest.class), any(Tenant.class))).thenReturn(mockBudget);
        when(budgetRepository.save(any())).thenReturn(mockBudget);
        when(budgetMapper.toResponse(any(AgentBudget.class), any())).thenReturn(BudgetResponse.builder().build());

        BudgetService.CreateBudgetResult result = budgetService.createBudget(request, tenant);

        assertThat(result.created()).isTrue();
        verify(budgetRepository).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CreateBudgetRequest validRequest() {
        CreateBudgetRequest req = new CreateBudgetRequest();
        req.setUserId("user_test");
        req.setTotalLimit(new BigDecimal("500.00"));
        req.setCurrency("USD");
        req.setExpiresAt(OffsetDateTime.now().plusHours(12));
        return req;
    }

    private AllocationRequest allocation(String category, BigDecimal limit) {
        AllocationRequest alloc = new AllocationRequest();
        alloc.setCategory(category);
        alloc.setAllowedCategories(List.of(category));
        alloc.setLimit(limit);
        return alloc;
    }
}
