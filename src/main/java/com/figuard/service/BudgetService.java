package com.figuard.service;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.request.FundBudgetRequest;
import com.figuard.api.dto.request.FundBudgetRequest.FundingOperation;
import com.figuard.api.dto.request.ResumeBudgetRequest;
import com.figuard.api.dto.request.UpdateBudgetRequest;
import com.figuard.api.dto.response.BudgetFundingResponse;
import com.figuard.api.dto.response.BudgetResponse;
import com.figuard.security.TraceIdFilter;
import org.slf4j.MDC;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.EntitlementItemRepository;
import com.figuard.exception.BudgetNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    /** Returned from createBudget so the controller can distinguish 201 Created vs 200 OK. */
    public record CreateBudgetResult(BudgetResponse budget, boolean created) {}

    private static final List<BudgetStatus> ACTIVE_STATUSES =
        List.of(BudgetStatus.ACTIVE, BudgetStatus.PAUSED);

    private final AgentBudgetRepository budgetRepository;
    private final EntitlementItemRepository entitlementItemRepository;
    private final SessionTokenService sessionTokenService;
    private final BudgetMapper budgetMapper;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;

    @Value("${agent-billing.budget.max-expiry-hours:24}")
    private int maxExpiryHours;

    @Value("${agent-billing.budget.default-first-authorize-deadline-seconds:900}")
    private int firstAuthorizeDeadlineSeconds;

    /** Applied when a budget is created without an explicit authorizationExpirySeconds.
     *  Reservations older than this window age out of the available-quantity calculation,
     *  preventing orphaned AUTHORIZED events from silently draining the budget. */
    @Value("${agent-billing.budget.default-authorization-expiry-seconds:300}")
    private int defaultAuthorizationExpirySeconds;

    @Value("${agent-billing.token.rotation-grace-period-seconds:60}")
    private int tokenRotationGraceSeconds;

    @Transactional
    public CreateBudgetResult createBudget(CreateBudgetRequest request, Tenant tenant) {
        validateExpiresAt(request.getExpiresAt());

        if (request.getAllocations() != null && !request.getAllocations().isEmpty()) {
            validateAllocations(request.getAllocations(), request.getTotalLimit());
        }

        // Idempotent creation: if externalReference is present and a live budget already
        // exists for this tenant, either return it (payload matches) or reject (conflict).
        if (request.getExternalReference() != null && !request.getExternalReference().isBlank()) {
            Optional<AgentBudget> existing = budgetRepository
                .findByTenantAndExternalReferenceAndStatusIn(
                    tenant, request.getExternalReference(), ACTIVE_STATUSES);

            if (existing.isPresent()) {
                AgentBudget live = existing.get();
                if (!payloadMatches(live, request)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A budget with externalReference '" + request.getExternalReference()
                        + "' already exists (id=" + live.getId()
                        + ") with a different configuration. "
                        + "Use the existing budget or choose a different externalReference.");
                }
                log.info("Idempotent budget hit: id={} tenant={} ref={}",
                    live.getId(), tenant.getId(), request.getExternalReference());
                return new CreateBudgetResult(budgetMapper.toResponse(live), false);
            }
        }

        // Build entity from request
        AgentBudget budget = budgetMapper.toEntity(request, tenant);

        // When entitlementItemId is provided, auto-populate subscriptionId from the item.
        // This ensures the subscription status gate (pause/cancel) fires correctly at authorization.
        if (request.getEntitlementItemId() != null) {
            EntitlementItem item = entitlementItemRepository.findById(request.getEntitlementItemId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "entitlementItemId " + request.getEntitlementItemId() + " not found"));
            budget.setSubscriptionId(item.getSubscription().getId());
        }

        // Apply default authorizationExpirySeconds when the caller doesn't specify one.
        // Prevents orphaned AUTHORIZED events from silently locking funds indefinitely
        // if an agent crashes or times out without calling confirm/fail/void.
        if (budget.getAuthorizationExpirySeconds() == null) {
            budget.setAuthorizationExpirySeconds(defaultAuthorizationExpirySeconds);
        }

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

        return new CreateBudgetResult(budgetMapper.toResponse(saved, rawToken), true);
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
    public Page<BudgetResponse> listBudgets(Tenant tenant, int page, int size, BudgetStatus status,
                                             boolean includeCancelled, String userId) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AgentBudget> budgets;
        if (userId != null && !userId.isBlank()) {
            if (status != null) {
                budgets = budgetRepository.findByTenantAndUserIdAndStatus(tenant, userId, status, pageable);
            } else if (includeCancelled) {
                budgets = budgetRepository.findByTenantAndUserId(tenant, userId, pageable);
            } else {
                budgets = budgetRepository.findByTenantAndUserIdAndStatusNot(tenant, userId, BudgetStatus.CANCELLED, pageable);
            }
        } else if (status != null) {
            // Explicit status filter: show exactly what was requested (including CANCELLED)
            budgets = budgetRepository.findByTenantAndStatus(tenant, status, pageable);
        } else if (includeCancelled) {
            budgets = budgetRepository.findByTenant(tenant, pageable);
        } else {
            // Default: exclude CANCELLED — keeps the dashboard focused on live budgets
            budgets = budgetRepository.findByTenantAndStatusNot(tenant, BudgetStatus.CANCELLED, pageable);
        }
        return budgets.map(budgetMapper::toResponse);
    }

    @Transactional
    public List<BudgetResponse> cancelBatch(List<UUID> budgetIds, Tenant tenant) {
        if (budgetIds == null || budgetIds.isEmpty()) {
            throw new IllegalArgumentException("budgetIds must not be empty");
        }
        if (budgetIds.size() > 100) {
            throw new IllegalArgumentException("Cannot cancel more than 100 budgets in a single batch");
        }

        List<AgentBudget> budgets = budgetRepository.findByTenantAndIdIn(tenant, budgetIds);

        List<BudgetResponse> results = new ArrayList<>();
        for (AgentBudget budget : budgets) {
            if (budget.getStatus() == BudgetStatus.CANCELLED
                    || budget.getStatus() == BudgetStatus.EXPIRED
                    || budget.getStatus() == BudgetStatus.EXHAUSTED) {
                // Already terminal — include in response without error
                results.add(budgetMapper.toResponse(budget));
                continue;
            }
            budget.setStatus(BudgetStatus.CANCELLED);
            budget.setCancelledAt(OffsetDateTime.now());
            budgetRepository.save(budget);
            log.info("Budget batch-cancelled: id={} tenant={}", budget.getId(), tenant.getId());
            results.add(budgetMapper.toResponse(budget));
        }
        return results;
    }

    /**
     * Extends the budget expiry window. The new expiresAt must be:
     * - In the future
     * - No more than 24h from now (same cap as creation — can be called repeatedly)
     * - Later than the current expiresAt (never shorten via this endpoint; use updateBudget for that)
     */
    @Transactional
    public BudgetResponse extendBudget(UUID id, OffsetDateTime newExpiresAt, Tenant tenant) {
        AgentBudget budget = budgetRepository.findByIdWithLock(id)
            .orElseThrow(() -> new BudgetNotFoundException(id));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(id);
        }

        if (budget.getStatus() == BudgetStatus.CANCELLED
                || budget.getStatus() == BudgetStatus.EXHAUSTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot extend a budget in terminal state: " + budget.getStatus());
        }

        validateExpiresAt(newExpiresAt);

        if (newExpiresAt.isBefore(budget.getExpiresAt()) || newExpiresAt.isEqual(budget.getExpiresAt())) {
            throw new IllegalArgumentException(
                "newExpiresAt must be later than the current expiresAt (" + budget.getExpiresAt() + ")");
        }

        budget.setExpiresAt(newExpiresAt);
        AgentBudget saved = budgetRepository.save(budget);
        log.info("Budget extended: id={} tenant={} newExpiresAt={}", id, tenant.getId(), newExpiresAt);
        return budgetMapper.toResponse(saved);
    }

    @Transactional
    public BudgetResponse updateBudget(UUID id, UpdateBudgetRequest request, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(id)
            .orElseThrow(() -> new BudgetNotFoundException(id));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(id);
        }

        boolean manuallyPausing = false;
        if (request.getStatus() != null) {
            // Only ACTIVE ↔ PAUSED transitions allowed via API
            if (request.getStatus() != BudgetStatus.ACTIVE
                    && request.getStatus() != BudgetStatus.PAUSED) {
                throw new IllegalArgumentException(
                    "Only ACTIVE or PAUSED status transitions are allowed via API");
            }
            manuallyPausing = request.getStatus() == BudgetStatus.PAUSED
                && budget.getStatus() != BudgetStatus.PAUSED;
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

        if (request.getVelocityMaxPerMinute() != null) {
            budget.setVelocityMaxPerMinute(request.getVelocityMaxPerMinute());
        }
        if (request.getVelocityMaxAmountPerHour() != null) {
            budget.setVelocityMaxAmountPerHour(request.getVelocityMaxAmountPerHour());
        }
        if (request.getVelocityMaxPerDay() != null) {
            budget.setVelocityMaxPerDay(request.getVelocityMaxPerDay());
        }

        AgentBudget saved = budgetRepository.save(budget);

        if (manuallyPausing) {
            webhookDispatcher.dispatch(
                tenant.getId(),
                WebhookEventType.BUDGET_PAUSED,
                webhookPayloadBuilder.buildBudgetPausedPayload(saved, "MANUAL"));
        }

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

    /**
     * Adjust a budget's totalLimit (and optionally quantitySpent) without re-creating it.
     *
     * CREDIT       — top up: totalLimit += amount
     * DEBIT        — reduce: totalLimit -= amount; rejected if result < quantitySpent
     * RESET        — set totalLimit to exactly amount; rejected if amount < quantitySpent
     * RESET_SPENT  — new billing period: quantitySpent = 0, totalLimit = amount
     *                quantityReserved is kept so in-flight authorizations still count.
     *
     * All operations use PESSIMISTIC_WRITE to prevent concurrent funding races.
     */
    @Transactional
    public BudgetFundingResponse fundBudget(UUID id, FundBudgetRequest request, Tenant tenant) {
        AgentBudget budget = budgetRepository.findByIdWithLock(id)
            .orElseThrow(() -> new BudgetNotFoundException(id));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(id);
        }

        if (budget.getStatus() == BudgetStatus.CANCELLED
                || budget.getStatus() == BudgetStatus.EXHAUSTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot fund a budget in terminal state: " + budget.getStatus());
        }

        BigDecimal previousTotalLimit = budget.getTotalLimit();
        FundingOperation op = request.getOperation();
        BigDecimal amount = request.getAmount();

        switch (op) {
            case CREDIT -> budget.setTotalLimit(budget.getTotalLimit().add(amount));
            case DEBIT -> {
                BigDecimal newLimit = budget.getTotalLimit().subtract(amount);
                if (newLimit.compareTo(budget.getQuantitySpent()) < 0) {
                    throw new IllegalArgumentException(
                        "DEBIT of " + amount + " would set totalLimit to " + newLimit
                        + " which is below quantitySpent (" + budget.getQuantitySpent() + ")");
                }
                budget.setTotalLimit(newLimit);
            }
            case RESET -> {
                if (amount.compareTo(budget.getQuantitySpent()) < 0) {
                    throw new IllegalArgumentException(
                        "RESET amount " + amount + " is below quantitySpent ("
                        + budget.getQuantitySpent() + ")");
                }
                budget.setTotalLimit(amount);
            }
            case RESET_SPENT -> {
                budget.setQuantitySpent(BigDecimal.ZERO);
                budget.setTotalLimit(amount);
                // Reactivate an EXHAUSTED budget if there's headroom again
                if (budget.getStatus() == BudgetStatus.EXHAUSTED) {
                    budget.setStatus(BudgetStatus.ACTIVE);
                }
            }
        }

        AgentBudget saved = budgetRepository.save(budget);
        log.info("Budget funded: id={} tenant={} op={} amount={} newLimit={}",
            id, tenant.getId(), op, amount, saved.getTotalLimit());

        return BudgetFundingResponse.builder()
            .budgetId(saved.getId())
            .operation(op)
            .amount(amount)
            .reason(request.getReason())
            .previousTotalLimit(previousTotalLimit)
            .totalLimit(saved.getTotalLimit())
            .quantitySpent(saved.getQuantitySpent())
            .quantityReserved(saved.getQuantityReserved())
            .availableQuantity(saved.availableQuantity())
            .status(saved.getStatus())
            .updatedAt(java.time.OffsetDateTime.now())
            .traceId(MDC.get(TraceIdFilter.TRACE_ID_KEY))
            .build();
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true when the existing budget's configuration is functionally equivalent
     * to the incoming request — safe to return the existing budget idempotently.
     *
     * expiresAt is intentionally excluded: agents always compute it relative to now(),
     * so it would always differ on a retry. The key invariants are the spend limits and
     * allocation structure.
     */
    private boolean payloadMatches(AgentBudget existing, CreateBudgetRequest req) {
        if (existing.getTotalLimit().compareTo(req.getTotalLimit()) != 0) return false;
        if (!Objects.equals(existing.getCurrency(), req.getCurrency())) return false;
        if (!Objects.equals(existing.getUnit(), req.getUnit())) return false;
        if (!nullSafeCompareTo(existing.getSoftLimit(), req.getSoftLimit())) return false;
        if (!nullSafeCompareTo(existing.getMaxTransactionQuantity(), req.getMaxTransactionQuantity())) return false;

        // Allocation count must match
        List<BudgetAllocation> existingAllocs = existing.getAllocations();
        List<AllocationRequest> reqAllocs = req.getAllocations();
        int existingCount = existingAllocs == null ? 0 : existingAllocs.size();
        int reqCount = reqAllocs == null ? 0 : reqAllocs.size();
        if (existingCount != reqCount) return false;

        // If there are allocations, compare by category + limit (order-insensitive)
        if (existingCount > 0) {
            for (AllocationRequest reqAlloc : reqAllocs) {
                boolean matched = existingAllocs.stream().anyMatch(ea ->
                    ea.getCategory().equals(reqAlloc.getCategory())
                    && ea.getTotalLimit().compareTo(reqAlloc.getLimit()) == 0);
                if (!matched) return false;
            }
        }

        return true;
    }

    private boolean nullSafeCompareTo(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private void validateExpiresAt(OffsetDateTime expiresAt) {
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        OffsetDateTime maxAllowed = OffsetDateTime.now().plusHours(maxExpiryHours);
        if (expiresAt.isAfter(maxAllowed)) {
            throw new IllegalArgumentException(
                "expiresAt cannot be more than " + maxExpiryHours + " hours in the future. " +
                "For longer-lived agent sessions, call POST /budgets/{id}/extend before the budget " +
                "expires — it can be called repeatedly to keep the session alive.");
        }
    }

    private void validateAllocations(List<AllocationRequest> allocations, BigDecimal totalLimit) {
        BigDecimal sum = allocations.stream()
            .map(AllocationRequest::getLimit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Hard error: allocations cannot exceed the total
        if (sum.compareTo(totalLimit) > 0) {
            BigDecimal excess = sum.subtract(totalLimit);
            throw new IllegalArgumentException(
                "Allocation limits sum (" + sum + ") exceeds totalLimit (" + totalLimit + ") " +
                "by " + excess + ". Reduce allocation limits or increase totalLimit.");
        }

        // sum < totalLimit is valid — the remainder is a free pool agents can spend
        // without a claimedCategory (useful for soft guardrails on specific categories).
        if (sum.compareTo(totalLimit) < 0) {
            BigDecimal freePool = totalLimit.subtract(sum);
            log.info("Budget has unallocated free pool of {} — agents can spend from it without claimedCategory",
                freePool);
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
