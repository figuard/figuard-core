package com.figuard.service;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.request.ResumeBudgetRequest;
import com.figuard.api.dto.request.UpdateBudgetRequest;
import com.figuard.api.dto.response.BudgetResponse;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.exception.BudgetNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final AgentBudgetRepository budgetRepository;
    private final SessionTokenService sessionTokenService;
    private final BudgetMapper budgetMapper;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;

    @Value("${agent-billing.budget.max-expiry-hours:24}")
    private int maxExpiryHours;

    @Value("${agent-billing.budget.default-first-authorize-deadline-seconds:900}")
    private int firstAuthorizeDeadlineSeconds;

    @Value("${agent-billing.token.rotation-grace-period-seconds:60}")
    private int tokenRotationGraceSeconds;

    @Transactional
    public BudgetResponse createBudget(CreateBudgetRequest request, Tenant tenant) {
        validateExpiresAt(request.getExpiresAt());

        if (request.getAllocations() != null && !request.getAllocations().isEmpty()) {
            validateAllocations(request.getAllocations(), request.getTotalLimit());
        }

        // Build entity from request
        AgentBudget budget = budgetMapper.toEntity(request, tenant);

        // Generate session token — hash stored, raw token returned once in response only
        String rawToken = sessionTokenService.generateToken();
        budget.setSessionTokenHash(sessionTokenService.hashToken(rawToken));
        budget.setSessionTokenPrefix(sessionTokenService.extractPrefix(rawToken));
        budget.setFirstAuthorizeDeadline(
            OffsetDateTime.now().plusSeconds(firstAuthorizeDeadlineSeconds));

        // Attach allocations so they save via CascadeType.ALL in one transaction
        if (request.getAllocations() != null && !request.getAllocations().isEmpty()) {
            List<BudgetAllocation> allocations = new ArrayList<>();
            for (AllocationRequest allocReq : request.getAllocations()) {
                allocations.add(budgetMapper.toEntity(allocReq, budget, tenant));
            }
            budget.setAllocations(allocations);
        }

        AgentBudget saved = budgetRepository.save(budget);

        log.info("Budget created: id={} tenant={} prefix={}",
            saved.getId(), tenant.getId(), saved.getSessionTokenPrefix());

        return budgetMapper.toResponse(saved, rawToken);
    }

    @Transactional(readOnly = true)
    public BudgetResponse getBudget(UUID id, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(id)
            .orElseThrow(() -> new BudgetNotFoundException(id));

        // Don't reveal existence of budgets belonging to other tenants
        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(id);
        }

        return budgetMapper.toResponse(budget);   // sessionToken = null on reads
    }

    @Transactional(readOnly = true)
    public Page<BudgetResponse> listBudgets(Tenant tenant, int page, int size, BudgetStatus status) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AgentBudget> budgets = status != null
                ? budgetRepository.findByTenantAndStatus(tenant, status, pageable)
                : budgetRepository.findByTenant(tenant, pageable);
        return budgets.map(budgetMapper::toResponse);
    }

    @Transactional
    public BudgetResponse updateBudget(UUID id, UpdateBudgetRequest request, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(id)
            .orElseThrow(() -> new BudgetNotFoundException(id));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(id);
        }

        if (request.getStatus() != null) {
            // Only ACTIVE ↔ PAUSED transitions allowed via API
            if (request.getStatus() != BudgetStatus.ACTIVE
                    && request.getStatus() != BudgetStatus.PAUSED) {
                throw new IllegalArgumentException(
                    "Only ACTIVE or PAUSED status transitions are allowed via API");
            }
            budget.setStatus(request.getStatus());
        }

        if (request.getTotalLimit() != null) {
            if (request.getTotalLimit().compareTo(budget.getQuantitySpent()) < 0) {
                throw new IllegalArgumentException(
                    "totalLimit cannot be less than quantitySpent (" + budget.getQuantitySpent() + ")");
            }
            budget.setTotalLimit(request.getTotalLimit());
        }

        if (request.getExpiresAt() != null) {
            validateExpiresAt(request.getExpiresAt());
            budget.setExpiresAt(request.getExpiresAt());
        }

        AgentBudget saved = budgetRepository.save(budget);
        return budgetMapper.toResponse(saved);
    }

    @Transactional
    public BudgetResponse cancelBudget(UUID budgetId, Tenant tenant) {
        // PESSIMISTIC_WRITE — same lock as authorize. Ensures cancel and authorize
        // cannot run concurrently. Whoever grabs the lock first wins; the other
        // sees the committed state and reacts accordingly.
        AgentBudget budget = budgetRepository.findByIdWithLock(budgetId)
            .orElseThrow(() -> new BudgetNotFoundException(budgetId));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(budgetId);
        }

        // Terminal states cannot be cancelled again
        if (budget.getStatus() == BudgetStatus.CANCELLED
                || budget.getStatus() == BudgetStatus.EXPIRED
                || budget.getStatus() == BudgetStatus.EXHAUSTED) {
            throw new IllegalArgumentException(
                "Budget is already in terminal state: " + budget.getStatus());
        }

        budget.setStatus(BudgetStatus.CANCELLED);
        budget.setCancelledAt(OffsetDateTime.now());

        AgentBudget saved = budgetRepository.save(budget);
        log.info("Budget cancelled: id={} tenant={}", budgetId, tenant.getId());
        return budgetMapper.toResponse(saved);
    }

    @Transactional
    public BudgetResponse resumeBudget(UUID budgetId, ResumeBudgetRequest request, Tenant tenant) {
        AgentBudget budget = budgetRepository.findByIdWithLock(budgetId)
            .orElseThrow(() -> new BudgetNotFoundException(budgetId));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(budgetId);
        }

        if (budget.getStatus() != BudgetStatus.PAUSED) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Budget is not PAUSED (current status: " + budget.getStatus() + ")");
        }

        budget.setStatus(BudgetStatus.ACTIVE);
        AgentBudget saved = budgetRepository.save(budget);

        log.info("Budget resumed: id={} tenant={} reason={} by={}",
            budgetId, tenant.getId(), request.getOverrideReason(), request.getOverrideBy());

        // Fire BUDGET_RESUMED webhook asynchronously
        webhookDispatcher.dispatch(
            tenant.getId(),
            WebhookEventType.BUDGET_RESUMED,
            webhookPayloadBuilder.buildBudgetResumedPayload(
                saved, request.getOverrideReason(), request.getOverrideBy()));

        return budgetMapper.toResponse(saved);
    }

    @Transactional
    public String rotateSessionToken(UUID budgetId, Tenant tenant) {
        AgentBudget budget = budgetRepository.findByIdWithLock(budgetId)
            .orElseThrow(() -> new BudgetNotFoundException(budgetId));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(budgetId);
        }

        // Move current hash to previous; new token takes over immediately.
        // Old token remains valid for the grace window so in-flight agents finish cleanly.
        String newRawToken = sessionTokenService.generateToken();
        budget.setPreviousSessionTokenHash(budget.getSessionTokenHash());
        budget.setTokenRotationExpiresAt(
            OffsetDateTime.now().plusSeconds(tokenRotationGraceSeconds));
        budget.setSessionTokenHash(sessionTokenService.hashToken(newRawToken));
        budget.setSessionTokenPrefix(sessionTokenService.extractPrefix(newRawToken));

        budgetRepository.save(budget);

        log.info("Token rotated: budgetId={} newPrefix={}", budgetId, budget.getSessionTokenPrefix());

        return newRawToken;
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void validateExpiresAt(OffsetDateTime expiresAt) {
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        OffsetDateTime maxAllowed = OffsetDateTime.now().plusHours(maxExpiryHours);
        if (expiresAt.isAfter(maxAllowed)) {
            throw new IllegalArgumentException(
                "expiresAt must not be more than " + maxExpiryHours + " hours in the future");
        }
    }

    private void validateAllocations(List<AllocationRequest> allocations, BigDecimal totalLimit) {
        // Sum of allocation limits must equal totalLimit exactly
        BigDecimal sum = allocations.stream()
            .map(AllocationRequest::getLimit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (sum.compareTo(totalLimit) != 0) {
            throw new IllegalArgumentException(
                "Allocation limits sum (" + sum + ") must equal totalLimit (" + totalLimit + ")");
        }

        // Category names must be unique within this budget
        Set<String> seen = new HashSet<>();
        for (AllocationRequest alloc : allocations) {
            if (!seen.add(alloc.getCategory())) {
                throw new IllegalArgumentException(
                    "Duplicate allocation category: '" + alloc.getCategory() + "'");
            }
        }
    }
}
