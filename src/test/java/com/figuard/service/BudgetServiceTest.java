package com.figuard.service;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.response.BudgetResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.AgentBudgetRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock AgentBudgetRepository budgetRepository;
    @Mock SessionTokenService sessionTokenService;
    @Mock BudgetMapper budgetMapper;

    @InjectMocks BudgetService budgetService;

    private Tenant tenant;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(budgetService, "maxExpiryHours", 24);
        ReflectionTestUtils.setField(budgetService, "firstAuthorizeDeadlineSeconds", 900);

        tenant = new Tenant();
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

        BudgetResponse response = budgetService.createBudget(request, tenant);

        // Capture what was saved — verify raw token is NOT in any persisted field
        ArgumentCaptor<AgentBudget> captor = ArgumentCaptor.forClass(AgentBudget.class);
        verify(budgetRepository).save(captor.capture());
        AgentBudget saved = captor.getValue();

        assertThat(saved.getSessionTokenHash()).isEqualTo(tokenHash);
        assertThat(saved.getSessionTokenHash()).isNotEqualTo(rawToken);

        // Raw token IS in the response (returned once) but not in persisted entity
        assertThat(response.getSessionToken()).isEqualTo(rawToken);
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
