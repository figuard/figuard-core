package com.figuard.service;

import com.figuard.api.dto.request.AuthorizeSpendRequest;
import com.figuard.api.dto.response.AllocationSnapshot;
import com.figuard.api.dto.response.AuthorizationResponse;
import com.figuard.api.dto.response.BudgetSnapshot;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.DelegatedToken;
import com.figuard.domain.entity.DelegatedTokenAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.TrustMode;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.repository.DelegatedTokenAllocationRepository;
import com.figuard.domain.repository.DelegatedTokenRepository;
import com.figuard.domain.repository.EntitlementItemRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.domain.repository.SubscriptionRepository;
import com.figuard.security.TraceIdFilter;
import com.figuard.service.model.MatchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final AgentBudgetRepository budgetRepository;
    private final BudgetAllocationRepository allocationRepository;
    private final SpendEventRepository spendEventRepository;
    private final CategoryMatchingService categoryMatchingService;
    private final SessionTokenService sessionTokenService;
    private final IntentScopeValidator intentScopeValidator;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final BudgetMapper budgetMapper;
    private final BudgetAnomalyBaselineRepository anomalyBaselineRepository;
    private final DelegatedTokenRepository delegatedTokenRepository;
    private final DelegatedTokenAllocationRepository delegatedTokenAllocationRepository;
    private final EntitlementEnforcementService entitlementEnforcementService;
    private final SubscriptionRepository subscriptionRepository;
    private final MeterRegistry meterRegistry;

    @Value("${agent-billing.authorization.confirmation-timeout-seconds:300}")
    private int confirmationTimeoutSeconds;

    @Value("${agent-billing.authorization.expiry-grace-seconds:60}")
    private int expiryGraceSeconds;

    @Transactional
    public AuthorizationResponse authorize(String rawSessionToken, AuthorizeSpendRequest request, Tenant requestTenant) {

        // Normalize claimedCategory to lowercase so "Flight" and "flight" match the same allocation.
        if (request.getClaimedCategory() != null) {
            request.setClaimedCategory(request.getClaimedCategory().toLowerCase());
        }

        // Step 1 — Hash token and find budget with pessimistic lock.
        // Primary path: direct budget token (supports rotation grace window).
        // Fallback path: delegation token → load parent budget with pessimistic lock.
        String tokenHash = sessionTokenService.hashToken(rawSessionToken);
        AgentBudget budget;
        DelegatedToken delegatedToken = null;

        Optional<AgentBudget> directBudget = budgetRepository
            .findBySessionTokenHashOrPrevious(tokenHash, OffsetDateTime.now());

        if (directBudget.isPresent()) {
            budget = directBudget.get();
        } else {
            delegatedToken = delegatedTokenRepository.findActiveBySessionTokenHash(tokenHash)
                .orElseThrow(() -> {
                    // Detect common integration mistake: developer passes budget ID instead of session token.
                    // Budget IDs are UUIDs; session tokens start with "st_".
                    if (rawSessionToken != null
                            && rawSessionToken.matches(
                                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "The value looks like a budget ID (UUID format). " +
                            "Pass sessionToken from the create_budget response, not id. " +
                            "Session tokens start with 'st_'.");
                    }
                    return new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, DenialCode.INVALID_SESSION_TOKEN.name());
                });

            // Load the parent (fleet) budget with a pessimistic write lock.
            // Lock acquired here, before any allocation or delegate-cap locks, to maintain
            // consistent lock ordering across all concurrent authorize transactions.
            budget = budgetRepository.findByIdWithLock(delegatedToken.getParentBudget().getId())
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Parent budget missing for delegation token"));
        }

        // Step 1b — Lock entitlement item (entitlement-backed budgets only).
        // Lock acquired immediately after budget lock to maintain consistent ordering:
        //   budget → entitlement item → delegate cap → fleet allocation.
        // Null for standalone budgets (unknown-user path — existing behavior unchanged).
        EntitlementItem entitlementItem = null;
        if (budget.getEntitlementItemId() != null) {
            entitlementItem = entitlementEnforcementService.loadWithLock(budget.getEntitlementItemId());

            // Subscription-level status gate — check subscription is active before going further
            if (budget.getSubscriptionId() != null) {
                subscriptionRepository.findById(budget.getSubscriptionId()).ifPresent(sub -> {
                    switch (sub.getStatus()) {
                        case PAUSED    -> throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                                DenialCode.SUBSCRIPTION_PAUSED.name());
                        case CANCELLED -> throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                                DenialCode.SUBSCRIPTION_CANCELLED.name());
                        default -> { /* ACTIVE — continue */ }
                    }
                });
            }
        }

        // Shadow mode: run all enforcement with dry-run semantics (no writes, no webhooks).
        // The response always returns AUTHORIZED but includes wouldHaveBeen + wouldHaveBeenReason
        // so the caller can see what full enforcement would have done.
        // Flip budget.trustMode to FULL_ENFORCEMENT via PATCH /budgets/{id} when ready.
        boolean shadowMode = budget.getTrustMode() == TrustMode.SHADOW;
        if (shadowMode) {
            request.setDryRun(true);
        }

        // Step 2 — Idempotency check (skipped for dry-run — nothing was ever written)
        // Must happen before any writes. If a duplicate key is found, return the original
        // decision WITHOUT creating a new SpendEvent. This handles agent retries safely.
        //
        // Exception: transient denials are bypassed when the underlying state has recovered.
        // An agent that retries after a velocity window clears, a budget is resumed, or a
        // budget is funded should get a fresh evaluation — not a cached stale denial.
        // See shouldBypassIdempotencyCache() for the full policy.
        if (!request.isDryRun()) {
            var existingEvent = spendEventRepository
                .findByBudgetIdAndIdempotencyKey(budget.getId(), request.getIdempotencyKey());
            if (existingEvent.isPresent()) {
                SpendEvent cached = existingEvent.get();
                if (shouldBypassIdempotencyCache(cached, budget)) {
                    log.info("Idempotent bypass (state recovered): budgetId={} key={} cachedDecision={} reason={}",
                        budget.getId(), request.getIdempotencyKey(),
                        cached.getDecision(), cached.getDenialReason());
                    // Fall through to fresh evaluation
                } else {
                    log.info("Idempotent hit: budgetId={} key={} decision={}",
                        budget.getId(), request.getIdempotencyKey(), cached.getDecision());
                    return buildResponse(cached, budget, null);
                }
            }
        }

        // Step 2b — Entity deduplication check (skipped for dry-run)
        // Only active when entityDedupEnabled=true on the budget AND the request carries an entityId.
        // Prevents two concurrent or sequential authorizations for the same real-world entity
        // (e.g. same flight booking) from both going AUTHORIZED on this budget.
        if (!request.isDryRun() && budget.isEntityDedupEnabled() && request.getEntityId() != null) {
            List<SpendEvent> duplicates = spendEventRepository
                .findByBudgetIdAndEntityIdAndDecisionIn(
                    budget.getId(),
                    request.getEntityId(),
                    List.of(SpendDecision.AUTHORIZED, SpendDecision.CONFIRMED));
            if (!duplicates.isEmpty()) {
                SpendEvent original = duplicates.get(0);
                log.info("Entity dedup hit: budgetId={} entityId={} existingEventId={}",
                    budget.getId(), request.getEntityId(), original.getId());
                SpendEvent denial = buildEvent(budget, null, request, null,
                    SpendDecision.DENIED, DenialCode.ENTITY_ALREADY_AUTHORIZED.name(),
                    "entityId '" + request.getEntityId() + "' already has a live event on this budget",
                    null, delegatedToken);
                SpendEvent savedDenial = spendEventRepository.save(denial);
                return buildResponse(savedDenial, budget, null, original.getId());
            }
        }

        // Step 3 — Tenant ownership check
        // 403 here (not 404) because the token itself was valid — the mismatch is the issue.
        if (!budget.getTenant().getId().equals(requestTenant.getId())) {
            log.warn("Tenant mismatch: budgetTenant={} requestTenant={}",
                budget.getTenant().getId(), requestTenant.getId());
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, DenialCode.TENANT_MISMATCH.name());
        }

        // Step 4 — Budget status check
        // parentEvent is not yet resolved at this point (validation happens in step 7),
        // so status denials are recorded without a parent link.
        switch (budget.getStatus()) {
            case PAUSED    -> { AuthorizationResponse r = deny(budget, null, request, null, DenialCode.BUDGET_PAUSED,    "Budget is paused", delegatedToken); return shadowMode ? shadowWrap(r, budget) : r; }
            case CANCELLED -> { AuthorizationResponse r = deny(budget, null, request, null, DenialCode.BUDGET_CANCELLED, "Budget has been cancelled", delegatedToken); return shadowMode ? shadowWrap(r, budget) : r; }
            case EXHAUSTED -> { AuthorizationResponse r = deny(budget, null, request, null, DenialCode.BUDGET_EXHAUSTED, "Budget is exhausted", delegatedToken); return shadowMode ? shadowWrap(r, budget) : r; }
            default -> { /* ACTIVE — continue */ }
        }

        // Step 5 — Expiry check with grace buffer
        // A 60-second grace window prevents race conditions where the budget expires
        // between the agent checking and sending the request.
        OffsetDateTime effectiveExpiry = budget.getExpiresAt().plusSeconds(expiryGraceSeconds);
        if (OffsetDateTime.now().isAfter(effectiveExpiry)) {
            AuthorizationResponse r = deny(budget, null, request, null, DenialCode.BUDGET_EXPIRED, "Budget has expired", delegatedToken);
            return shadowMode ? shadowWrap(r, budget) : r;
        }

        // Step 5c — Entitlement balance check (entitlement-backed budgets only).
        // Runs after expiry/status checks and before velocity/category/funds checks.
        // WARN_ONLY policy: limit reached but spend continues; no denial returned.
        // BLOCK policy: returns denial immediately.
        if (entitlementItem != null) {
            EntitlementEnforcementService.CheckResult entitlementResult =
                    entitlementEnforcementService.check(entitlementItem, request.getRequestedQuantity());
            if (entitlementResult.isDenied()) {
                EntitlementEnforcementService.CheckResult.Denied denied =
                        (EntitlementEnforcementService.CheckResult.Denied) entitlementResult;
                AuthorizationResponse r = deny(budget, null, request, null, denied.code(), denied.message(), delegatedToken);
                return shadowMode ? shadowWrap(r, budget) : r;
            }
        }

        // Step 5b — Velocity controls (rolling-window rate limits)
        // Checked after expiry/grace and before category/currency matching.
        // All authorize attempts count toward the window regardless of outcome.
        // The count/sum queries run against the live spend_events table via existing index.
        if (budget.getVelocityMaxPerMinute() != null
                || budget.getVelocityMaxAmountPerHour() != null
                || budget.getVelocityMaxPerDay() != null) {
            AuthorizationResponse velocityDenial = checkVelocity(budget, request, delegatedToken);
            if (velocityDenial != null) return shadowMode ? shadowWrap(velocityDenial, budget) : velocityDenial;
        }

        // Step 6 — Currency check (monetary budgets only)
        // Resource budgets (unit set, currency null) skip this check entirely.
        if (budget.isMonetary()) {
            String requestCurrency = request.getCurrency() != null ? request.getCurrency() : "USD";
            String budgetCurrency  = budget.getCurrency().trim();
            if (!requestCurrency.equalsIgnoreCase(budgetCurrency)) {
                AuthorizationResponse r = deny(budget, null, request, null, DenialCode.CURRENCY_MISMATCH,
                    "Currency mismatch: requested " + requestCurrency + " but budget is " + budgetCurrency,
                    delegatedToken);
                return shadowMode ? shadowWrap(r, budget) : r;
            }
        }

        // Step 7 — Per-transaction ceiling check
        // Only enforced when maxTransactionQuantity is set on the budget.
        // Checked before funds availability — a request that exceeds the ceiling is denied
        // even if the budget has plenty of remaining funds.
        if (budget.getMaxTransactionQuantity() != null
                && request.getRequestedQuantity().compareTo(budget.getMaxTransactionQuantity()) > 0) {
            AuthorizationResponse r = deny(budget, null, request, null, DenialCode.EXCEEDS_QUANTITY_LIMIT,
                "requestedQuantity " + request.getRequestedQuantity() +
                " exceeds per-transaction limit of " + budget.getMaxTransactionQuantity(),
                delegatedToken);
            return shadowMode ? shadowWrap(r, budget) : r;
        }

        // Step 7a — Anomaly detection
        // Only runs when anomalyDetectionEnabled=true and the baseline has enough samples
        // (anomalyMinSampleSize guard prevents false positives on brand-new budgets).
        // Skipped for resource budgets (unit set, currency null) — anomaly thresholds are
        // calibrated for monetary amounts and produce false positives on token/call counts.
        if (budget.isAnomalyDetectionEnabled() && budget.isMonetary()) {
            var anomalyResult = checkAnomaly(budget, request.getRequestedQuantity(), request);
            if (anomalyResult != null) return shadowMode ? shadowWrap(anomalyResult, budget) : anomalyResult;
        }

        // Step 8 — Parent event validation (causal chain)
        SpendEvent parentEvent = null;
        if (request.getParentEventId() != null) {
            parentEvent = validateParentEvent(request.getParentEventId(), budget.getId());
        }

        // Step 8b — Per-chain subtree spend cap
        // When the root of this causal chain has maxSubtreeQuantity set, verify that
        // authorizing this request would not push the chain total over the cap.
        // Checked after parent validation so chainRootEventId is available via parentEvent.
        // Only runs for non-root events (parentEvent != null) where the chain root is known.
        if (parentEvent != null && parentEvent.getChainRootEventId() != null) {
            UUID chainRootId = parentEvent.getChainRootEventId();
            SpendEvent chainRoot = spendEventRepository.findById(chainRootId).orElse(null);
            if (chainRoot != null && chainRoot.getMaxSubtreeQuantity() != null) {
                BigDecimal subtreeTotal = spendEventRepository.sumSubtreeQuantity(
                    chainRootId, List.of(SpendDecision.AUTHORIZED, SpendDecision.CONFIRMED));
                BigDecimal projected = subtreeTotal.add(request.getRequestedQuantity());
                if (projected.compareTo(chainRoot.getMaxSubtreeQuantity()) > 0) {
                    BigDecimal remaining = chainRoot.getMaxSubtreeQuantity()
                        .subtract(subtreeTotal).max(BigDecimal.ZERO);
                    AuthorizationResponse subtreeR = deny(budget, null, request, parentEvent,
                        DenialCode.SUBTREE_CAP_EXCEEDED,
                        "chain total " + projected + " would exceed chain cap of " +
                        chainRoot.getMaxSubtreeQuantity() + "; remaining capacity: " + remaining,
                        delegatedToken);
                    return shadowMode ? shadowWrap(subtreeR, budget) : subtreeR;
                }
            }
        }

        // Step 9 — Resolve effective reserved quantity
        // When authorizationExpirySeconds is set, exclude stale AUTHORIZED events
        // from the capacity calculation. This implements lazy auto-expiry: orphaned
        // reservations become invisible to the capacity check without a sweep job.
        // The stale events remain in the ledger for audit; they simply stop holding capacity.
        BigDecimal effectiveReserved = resolveEffectiveReserved(budget);

        // Step 10 — Load allocations
        List<BudgetAllocation> allocations =
            allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(budget.getId());

        // Step 11 — Category matching and funds check
        AuthorizationResponse result;
        if (!allocations.isEmpty()) {
            result = authorizeWithAllocations(budget, allocations, request, parentEvent,
                effectiveReserved, delegatedToken, entitlementItem);
        } else {
            result = authorizeFlat(budget, request, parentEvent, effectiveReserved, delegatedToken,
                entitlementItem);
        }

        // Shadow mode: enforce ran with dryRun=true. Return AUTHORIZED regardless,
        // but populate wouldHaveBeen so the caller can see what enforcement would have done.
        if (shadowMode) {
            return AuthorizationResponse.builder()
                .shadow(true)
                .decision(SpendDecision.AUTHORIZED)
                .wouldHaveBeen(result.getDecision())
                .wouldHaveBeenReason(result.getDenialReason())
                .budgetSnapshot(result.getBudgetSnapshot())
                .traceId(MDC.get(TraceIdFilter.TRACE_ID_KEY))
                .build();
        }

        return result;
    }

    /**
     * Returns the effective reserved quantity for capacity checks.
     * When authorizationExpirySeconds is set, DB-computes the sum of AUTHORIZED events
     * within the expiry window. Otherwise returns the denormalized budget field.
     */
    private BigDecimal resolveEffectiveReserved(AgentBudget budget) {
        if (budget.getAuthorizationExpirySeconds() == null) {
            return budget.getQuantityReserved();
        }
        OffsetDateTime cutoff = OffsetDateTime.now()
            .minusSeconds(budget.getAuthorizationExpirySeconds());
        return spendEventRepository.sumAuthorizedQuantityAfter(budget.getId(), cutoff);
    }

    // -------------------------------------------------------------------------
    // Step 10a — Allocation path
    // -------------------------------------------------------------------------

    private AuthorizationResponse authorizeWithAllocations(AgentBudget budget,
                                                            List<BudgetAllocation> allocations,
                                                            AuthorizeSpendRequest request,
                                                            SpendEvent parentEvent,
                                                            BigDecimal effectiveReserved,
                                                            DelegatedToken delegatedToken,
                                                            EntitlementItem entitlementItem) {
        MatchResult matchResult = categoryMatchingService.findMatch(
            allocations, request.getClaimedCategory(), request.getClaimedItemType());

        return switch (matchResult) {
            case MatchResult.MissingCategory ignored -> {
                // Missing claimedCategory is returned as a structured DENIED rather than a 400.
                // LLMs and agents handle a DENIED decision cleanly; a raw 400 gives them nothing
                // to reason about. The event is recorded so the audit trail is complete.
                String availableCategories = allocations.stream()
                    .map(BudgetAllocation::getCategory)
                    .collect(Collectors.joining(", "));
                yield deny(budget, null, request, parentEvent, DenialCode.MISSING_CLAIMED_CATEGORY,
                    "This budget has allocations — claimedCategory is required. " +
                    "Set claimedCategory to one of: [" + availableCategories + "]", delegatedToken);
            }

            case MatchResult.NoMatch ignored -> {
                String availableCategories = allocations.stream()
                    .map(BudgetAllocation::getCategory)
                    .collect(Collectors.joining(", "));
                yield deny(budget, null, request, parentEvent, DenialCode.NO_MATCHING_ALLOCATION,
                    "No allocation found for '" + request.getClaimedCategory() + "'. " +
                    "Available: [" + availableCategories + "]. " +
                    "Category matching is case-insensitive — check for typos or plural/singular mismatches.",
                    delegatedToken);
            }

            case MatchResult.Forbidden f ->
                deny(budget, f.allocation(), request, parentEvent, DenialCode.FORBIDDEN_ITEM_TYPE,
                    "claimedItemType '" + f.itemType() + "' is forbidden on this allocation", delegatedToken);

            case MatchResult.Match m -> {
                BudgetAllocation matched = m.allocation();

                // Step A — Delegate cap check (before fleet allocation lock).
                // Lock order: budget (Step 1) → delegate allocation (here) → fleet allocation (below).
                // Only checked when request was made via a delegation token AND this category has a cap.
                DelegatedTokenAllocation lockedDelegateCap = null;
                if (delegatedToken != null) {
                    Optional<DelegatedTokenAllocation> delegateCap =
                        delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
                            delegatedToken.getId(), matched.getCategory());
                    if (delegateCap.isPresent()) {
                        lockedDelegateCap = delegateCap.get();
                        if (!lockedDelegateCap.canAccommodate(request.getRequestedQuantity())) {
                            yield deny(budget, matched, request, parentEvent,
                                DenialCode.DELEGATE_CAP_EXCEEDED,
                                "Delegation token cap for '" + matched.getCategory() + "' has " +
                                lockedDelegateCap.availableQuantity() + " available, requested " +
                                request.getRequestedQuantity(), delegatedToken);
                        }
                    }
                    // No cap for this category → pass through to fleet allocation check only
                }

                // Step B — Re-fetch the fleet allocation with pessimistic write lock.
                // This prevents two concurrent threads from both reading the same
                // available balance and both thinking they can proceed.
                BudgetAllocation lockedAllocation = allocationRepository
                    .findByIdWithLock(matched.getId())
                    .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Allocation disappeared under lock"));

                if (!lockedAllocation.canAccommodate(request.getRequestedQuantity())) {
                    AuthorizationResponse denialResponse = deny(budget, lockedAllocation, request, parentEvent,
                        DenialCode.ALLOCATION_EXHAUSTED,
                        "Allocation '" + lockedAllocation.getCategory() + "' has " +
                        lockedAllocation.availableQuantity() + " available, requested " +
                        request.getRequestedQuantity(), delegatedToken);
                    // Fire ALLOCATION_EXHAUSTED only when the allocation is truly at zero capacity —
                    // not just insufficient for this specific request. This prevents webhook spam
                    // when the allocation has some balance left but not enough for large requests.
                    if (!request.isDryRun() && lockedAllocation.availableQuantity().compareTo(BigDecimal.ZERO) == 0) {
                        webhookDispatcher.dispatch(
                            budget.getTenant().getId(),
                            WebhookEventType.ALLOCATION_EXHAUSTED,
                            webhookPayloadBuilder.buildAllocationExhaustedPayload(
                                budget, lockedAllocation,
                                denialResponse.getEventId(),
                                request.getRequestedQuantity()));
                    }
                    yield denialResponse;
                }

                // reserve=false holds no capacity — skip the budget-capacity check.
                if (request.isReserve()
                        && !budget.canAccommodateWith(request.getRequestedQuantity(), effectiveReserved)) {
                    yield deny(budget, lockedAllocation, request, parentEvent, DenialCode.INSUFFICIENT_FUNDS,
                        insufficientFundsMessage(budget, request, effectiveReserved), delegatedToken);
                }

                yield approve(budget, lockedAllocation, request, parentEvent, delegatedToken,
                    lockedDelegateCap, entitlementItem);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Step 10b — Flat budget path (no allocations)
    // -------------------------------------------------------------------------

    private AuthorizationResponse authorizeFlat(AgentBudget budget, AuthorizeSpendRequest request,
                                                 SpendEvent parentEvent, BigDecimal effectiveReserved,
                                                 DelegatedToken delegatedToken,
                                                 EntitlementItem entitlementItem) {
        // Intent scope check — only on flat budgets. Allocated budgets enforce intent via
        // claimedCategory; this check would be redundant and is intentionally skipped there.
        if (!intentScopeValidator.isInScope(budget.getIntentTags(), request.getIntentContext())) {
            String message = (request.getIntentContext() == null || request.getIntentContext().isBlank())
                ? "This budget requires intentContext — budget intentTags: " +
                  java.util.Arrays.toString(budget.getIntentTags())
                : "intentContext '" + request.getIntentContext() +
                  "' does not match any budget intentTags: " +
                  java.util.Arrays.toString(budget.getIntentTags());
            return deny(budget, null, request, parentEvent, DenialCode.INTENT_SCOPE_VIOLATION, message,
                delegatedToken);
        }

        // Delegate cap check — flat budgets have no fleet allocations, so we match
        // the cap by claimedCategory (if present) or fall back to any cap on the token.
        DelegatedTokenAllocation lockedDelegateCap = null;
        if (delegatedToken != null) {
            Optional<DelegatedTokenAllocation> delegateCap = (request.getClaimedCategory() != null)
                ? delegatedTokenAllocationRepository.findByTokenIdAndCategoryWithLock(
                    delegatedToken.getId(), request.getClaimedCategory())
                : delegatedTokenAllocationRepository.findFirstByTokenIdWithLock(delegatedToken.getId());
            if (delegateCap.isPresent()) {
                lockedDelegateCap = delegateCap.get();
                if (!lockedDelegateCap.canAccommodate(request.getRequestedQuantity())) {
                    return deny(budget, null, request, parentEvent,
                        DenialCode.DELEGATE_CAP_EXCEEDED,
                        "Delegation token cap for '" + lockedDelegateCap.getCategory() + "' has " +
                        lockedDelegateCap.availableQuantity() + " available, requested " +
                        request.getRequestedQuantity(), delegatedToken);
                }
            }
        }

        // reserve=false holds no capacity, so the budget-capacity check does not apply
        // (a tree-root/coordinator marker always proceeds — it reserves nothing).
        if (request.isReserve()
                && !budget.canAccommodateWith(request.getRequestedQuantity(), effectiveReserved)) {
            return deny(budget, null, request, parentEvent, DenialCode.INSUFFICIENT_FUNDS,
                insufficientFundsMessage(budget, request, effectiveReserved), delegatedToken);
        }
        return approve(budget, null, request, parentEvent, delegatedToken, lockedDelegateCap,
            entitlementItem);
    }

    /**
     * Build the INSUFFICIENT_FUNDS denial message. When capacity is held by unconfirmed
     * authorizations (reserved > 0) rather than actually spent, say so explicitly — otherwise
     * the caller sees "0 available" while nothing has been spent and cannot tell that a parent
     * or orchestrator reservation is holding the whole budget. Confirming or voiding those
     * outstanding authorizations releases the capacity.
     */
    private String insufficientFundsMessage(AgentBudget budget, AuthorizeSpendRequest request,
                                            BigDecimal effectiveReserved) {
        String base = "Budget has " + budget.availableQuantityWith(effectiveReserved)
            + " available, requested " + request.getRequestedQuantity();
        boolean heldByReservations = effectiveReserved.compareTo(BigDecimal.ZERO) > 0
            && budget.getQuantitySpent().compareTo(budget.getTotalLimit()) < 0;
        if (!heldByReservations) {
            return base;
        }
        return base + ". " + effectiveReserved + " is reserved by unconfirmed authorizations (only "
            + budget.getQuantitySpent() + " of " + budget.getTotalLimit()
            + " actually spent) — confirm or void them to free capacity";
    }

    // -------------------------------------------------------------------------
    // Step 11 — Reserve funds and write AUTHORIZED event
    // -------------------------------------------------------------------------

    private AuthorizationResponse approve(AgentBudget budget,
                                          BudgetAllocation allocation,
                                          AuthorizeSpendRequest request,
                                          SpendEvent parentEvent,
                                          DelegatedToken delegatedToken,
                                          DelegatedTokenAllocation delegateCap,
                                          EntitlementItem entitlementItem) {
        OffsetDateTime now = OffsetDateTime.now();
        SpendEvent event = buildEvent(budget, allocation, request, parentEvent, SpendDecision.AUTHORIZED,
            null, null, null, delegatedToken);
        event.setCreatedAt(now);
        event.setReserved(request.isReserve());
        // reserve=false (tree-root/coordinator marker): no confirmation timeout, so the
        // sweep never auto-voids it — it stays a stable chain root for the whole task.
        if (request.isReserve()) {
            event.setConfirmationTimeoutAt(now.plusSeconds(confirmationTimeoutSeconds));
        }

        // Dry-run: return the authorization decision without writing, reserving funds,
        // or firing webhooks. All enforcement checks above have already run.
        if (request.isDryRun()) {
            log.info("DRY_RUN AUTHORIZED: budgetId={} alloc={} quantity={} delegated={}",
                budget.getId(),
                allocation != null ? allocation.getCategory() : "flat",
                request.getRequestedQuantity(),
                delegatedToken != null ? delegatedToken.getId() : "false");
            return buildResponse(event, budget, allocation);
        }

        // Capture available quantity before reservation to detect threshold crossings
        BigDecimal prevAvailable = budget.availableQuantity();

        // reserve=false holds no capacity: skip every reservation write. The event is
        // still recorded (below) and can anchor a causal chain, but available/reserved
        // are unchanged at delegate-cap, allocation, and budget level.
        if (request.isReserve()) {
            // Reserve at delegation cap level (most specific) before fleet allocation and budget.
            // This keeps lock ordering consistent: budget → delegate cap → fleet allocation.
            if (delegateCap != null) {
                delegateCap.setQuantityReserved(
                    delegateCap.getQuantityReserved().add(request.getRequestedQuantity()));
                delegatedTokenAllocationRepository.save(delegateCap);
            }

            // Reserve at allocation level, then budget level
            if (allocation != null) {
                allocation.setQuantityReserved(
                    allocation.getQuantityReserved().add(request.getRequestedQuantity()));
                allocationRepository.save(allocation);
            }

            budget.setQuantityReserved(
                budget.getQuantityReserved().add(request.getRequestedQuantity()));
            budgetRepository.save(budget);
        }

        SpendEvent saved = spendEventRepository.save(event);

        // Root events (no parent) self-reference chainRootEventId, but the ID is only
        // available after the first save. Patch and re-save for root events only.
        if (parentEvent == null) {
            saved.setChainRootEventId(saved.getId());
            saved = spendEventRepository.save(saved);
        }

        // Consume from entitlement item (entitlement-backed budgets only).
        // Must happen after SpendEvent is saved so the event ID is available for linking.
        // reserve=false consumes nothing, including entitlement.
        if (entitlementItem != null && request.isReserve()) {
            entitlementEnforcementService.consume(entitlementItem, request.getRequestedQuantity(), saved);
        }

        log.info("AUTHORIZED: budgetId={} alloc={} quantity={} key={} delegated={} entitlement={}",
            budget.getId(),
            allocation != null ? allocation.getCategory() : "flat",
            request.getRequestedQuantity(),
            request.getIdempotencyKey(),
            delegatedToken != null ? delegatedToken.getId() : "false",
            entitlementItem != null ? entitlementItem.getId() : "none");

        // Dispatch threshold webhooks asynchronously — must not block the authorize response
        dispatchThresholdWebhooks(budget, prevAvailable);

        return buildResponse(saved, budget, allocation);
    }

    // -------------------------------------------------------------------------
    // Denial helper — every DENIED path writes a SpendEvent to the ledger
    // -------------------------------------------------------------------------

    /**
     * Wraps a denial (or authorized) response for shadow-mode budgets.
     * Returns AUTHORIZED with shadow=true, preserving wouldHaveBeen and wouldHaveBeenReason
     * so the caller can see what full enforcement would have done.
     */
    private AuthorizationResponse shadowWrap(AuthorizationResponse inner, AgentBudget budget) {
        return AuthorizationResponse.builder()
            .shadow(true)
            .decision(SpendDecision.AUTHORIZED)
            .wouldHaveBeen(inner.getDecision())
            .wouldHaveBeenReason(inner.getDenialReason())
            .budgetSnapshot(inner.getBudgetSnapshot() != null
                ? inner.getBudgetSnapshot() : budgetMapper.toBudgetSnapshot(budget))
            .traceId(MDC.get(TraceIdFilter.TRACE_ID_KEY))
            .build();
    }

    private AuthorizationResponse deny(AgentBudget budget,
                                       BudgetAllocation allocation,
                                       AuthorizeSpendRequest request,
                                       SpendEvent parentEvent,
                                       DenialCode code,
                                       String message,
                                       DelegatedToken delegatedToken) {
        SpendEvent event = buildEvent(budget, allocation, request, parentEvent,
            SpendDecision.DENIED, code.name(), message, null, delegatedToken);

        // Dry-run: return the denial decision without writing or firing webhooks.
        if (request.isDryRun()) {
            log.info("DRY_RUN DENIED: budgetId={} code={}", budget.getId(), code);
            return buildResponse(event, budget, allocation);
        }

        SpendEvent saved = spendEventRepository.save(event);

        // Root events self-reference chainRootEventId — patch after first save.
        if (parentEvent == null) {
            saved.setChainRootEventId(saved.getId());
            saved = spendEventRepository.save(saved);
        }

        log.info("DENIED: budgetId={} code={} key={}",
            budget.getId(), code, request.getIdempotencyKey());

        // Fire SPEND_DENIED webhook asynchronously
        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_DENIED,
            webhookPayloadBuilder.buildSpendDeniedPayload(budget, saved));

        return buildResponse(saved, budget, allocation);
    }

    // -------------------------------------------------------------------------
    // Anomaly detection (Step 7a)
    // -------------------------------------------------------------------------

    /**
     * Returns a denial response if the request is anomalous, null otherwise.
     * Pauses the budget synchronously within this transaction so no further
     * authorized events can proceed while the human reviews the alert.
     */
    private AuthorizationResponse checkAnomaly(AgentBudget budget,
                                                BigDecimal requestedAmount,
                                                AuthorizeSpendRequest request) {
        int minSamples = budget.getAnomalyMinSampleSize() != null
            ? budget.getAnomalyMinSampleSize() : 5;
        BigDecimal multiplier = budget.getAnomalyPauseThresholdMultiplier() != null
            ? budget.getAnomalyPauseThresholdMultiplier() : new BigDecimal("3.00");

        return anomalyBaselineRepository.findByBudgetId(budget.getId())
            .filter(baseline -> baseline.getSampleCount() >= minSamples)
            .filter(baseline -> baseline.getMeanAmount() != null
                && baseline.getMeanAmount().compareTo(BigDecimal.ZERO) > 0)
            .map(baseline -> {
                BigDecimal threshold = baseline.getMeanAmount().multiply(multiplier);
                if (requestedAmount.compareTo(threshold) > 0) {
                    return denyAnomaly(budget, request, baseline.getMeanAmount(), threshold);
                }
                return null;
            })
            .orElse(null);
    }

    /**
     * Records the denial, pauses the budget, and fires ANOMALY_DETECTED webhook.
     * Does NOT fire SPEND_DENIED — anomaly alerts are a distinct event type so
     * callers can route them to different Slack channels / on-call flows.
     */
    private AuthorizationResponse denyAnomaly(AgentBudget budget,
                                               AuthorizeSpendRequest request,
                                               BigDecimal baselineMean,
                                               BigDecimal threshold) {
        SpendEvent parentEvent = resolveParentForDenial(request, budget.getId());
        SpendEvent event = buildEvent(budget, null, request, parentEvent,
            SpendDecision.DENIED, DenialCode.ANOMALY_DETECTED.name(),
            "requestedQuantity " + request.getRequestedQuantity()
                + " exceeds anomaly threshold of " + threshold
                + " (mean=" + baselineMean + ")", null, null);
        SpendEvent saved = spendEventRepository.save(event);

        if (parentEvent == null) {
            saved.setChainRootEventId(saved.getId());
            saved = spendEventRepository.save(saved);
        }

        UUID tenantId = budget.getTenant().getId();

        if (budget.isAutoPauseOnAnomaly()) {
            // Pause the budget synchronously — same transaction, so the pause is
            // committed atomically with the denial record.
            budget.setStatus(com.figuard.domain.enums.BudgetStatus.PAUSED);
            budgetRepository.save(budget);
            log.warn("ANOMALY_DETECTED: budgetId={} requested={} threshold={} — budget PAUSED (autoPauseOnAnomaly=true)",
                budget.getId(), request.getRequestedQuantity(), threshold);
            // Fire BUDGET_PAUSED so orchestrators stop spawning new sub-agents immediately
            webhookDispatcher.dispatch(tenantId, WebhookEventType.BUDGET_PAUSED,
                webhookPayloadBuilder.buildBudgetPausedPayload(budget, "ANOMALY_DETECTED"));
        } else {
            // Advisory mode: deny the request but do not pause the budget.
            // The ANOMALY_DETECTED webhook still fires so orchestrators can react.
            log.warn("ANOMALY_DETECTED: budgetId={} requested={} threshold={} — advisory mode, budget stays ACTIVE",
                budget.getId(), request.getRequestedQuantity(), threshold);
        }

        // Fire ANOMALY_DETECTED webhook (not SPEND_DENIED) asynchronously
        String webhookUrl = budget.getAnomalyAlertWebhookUrl();
        if (webhookUrl != null) {
            // Dispatch to the dedicated anomaly URL if configured
            webhookDispatcher.dispatchToUrl(webhookUrl, tenantId,
                WebhookEventType.ANOMALY_DETECTED,
                webhookPayloadBuilder.buildAnomalyDetectedPayload(budget, saved, baselineMean, threshold));
        } else {
            webhookDispatcher.dispatch(tenantId, WebhookEventType.ANOMALY_DETECTED,
                webhookPayloadBuilder.buildAnomalyDetectedPayload(budget, saved, baselineMean, threshold));
        }

        return buildResponse(saved, budget, null);
    }

    // -------------------------------------------------------------------------
    // Idempotency cache bypass — transient denial state recovery
    // -------------------------------------------------------------------------

    /**
     * Returns true when a cached denial should be bypassed and the request re-evaluated.
     *
     * <p>Only transient denials are bypassed — those where the underlying state can recover
     * after the denial was written. Permanent denials (e.g. NO_MATCHING_ALLOCATION) are
     * always replayed from cache regardless of current state.
     *
     * <p>Cases:
     * <ul>
     *   <li>VELOCITY_LIMIT_EXCEEDED — bypass when the shortest velocity window has elapsed
     *       since the denial was written. Agent retrying after 1 minute should get a fresh check.
     *   <li>BUDGET_PAUSED / ANOMALY_DETECTED — bypass when the budget is no longer PAUSED.
     *       Operator resumes the budget; agent retrying same key should get through.
     *   <li>BUDGET_EXHAUSTED — bypass when available quantity is now positive.
     *       Budget was funded; agent retrying same key should get through.
     *   <li>All others — permanent; always replay from cache.
     * </ul>
     */
    private boolean shouldBypassIdempotencyCache(SpendEvent cached, AgentBudget budget) {
        if (cached.getDenialReason() == null) return false;
        return switch (cached.getDenialReason()) {
            case "VELOCITY_LIMIT_EXCEEDED" -> {
                Duration shortestWindow = resolveShortestVelocityWindow(budget);
                yield shortestWindow != null
                    && cached.getCreatedAt().isBefore(OffsetDateTime.now().minus(shortestWindow));
            }
            case "BUDGET_PAUSED", "ANOMALY_DETECTED" ->
                budget.getStatus() != com.figuard.domain.enums.BudgetStatus.PAUSED;
            case "BUDGET_EXHAUSTED" ->
                budget.availableQuantity().compareTo(java.math.BigDecimal.ZERO) > 0;
            default -> false;
        };
    }

    /**
     * Returns the shortest velocity window configured on the budget, used as the
     * idempotency bypass TTL for VELOCITY_LIMIT_EXCEEDED denials.
     *
     * Priority: per-minute > per-hour > per-day. Returns null if no velocity limits are set.
     */
    private Duration resolveShortestVelocityWindow(AgentBudget budget) {
        if (budget.getVelocityMaxPerMinute() != null)     return Duration.ofMinutes(1);
        if (budget.getVelocityMaxAmountPerHour() != null) return Duration.ofHours(1);
        if (budget.getVelocityMaxPerDay() != null)        return Duration.ofDays(1);
        return null;
    }

    // -------------------------------------------------------------------------
    // Velocity controls (Step 5b)
    // -------------------------------------------------------------------------

    /**
     * Checks all configured rolling-window rate limits in priority order:
     * per-minute count → hourly amount → per-day count.
     *
     * Returns a denial response if any limit is violated, null if all limits pass.
     * The first violated limit short-circuits — subsequent limits are not evaluated.
     */
    private AuthorizationResponse checkVelocity(AgentBudget budget,
                                                  AuthorizeSpendRequest request,
                                                  DelegatedToken delegatedToken) {
        OffsetDateTime now = OffsetDateTime.now();

        if (budget.getVelocityMaxPerMinute() != null) {
            OffsetDateTime cutoff = now.minusMinutes(1);
            long count = spendEventRepository.countAttemptsAfter(budget.getId(), cutoff);
            if (count >= budget.getVelocityMaxPerMinute()) {
                String desc = "maxPerMinute=" + budget.getVelocityMaxPerMinute()
                    + " (window count=" + count + ")";
                return denyVelocity(budget, request, delegatedToken, cutoff, desc);
            }
        }

        if (budget.getVelocityMaxAmountPerHour() != null) {
            OffsetDateTime cutoff = now.minusHours(1);
            BigDecimal windowSum = spendEventRepository.sumAttemptedQuantityAfter(budget.getId(), cutoff);
            if (windowSum.add(request.getRequestedQuantity())
                    .compareTo(budget.getVelocityMaxAmountPerHour()) > 0) {
                String desc = "maxAmountPerHour=" + budget.getVelocityMaxAmountPerHour()
                    + " (window sum=" + windowSum
                    + ", requested=" + request.getRequestedQuantity() + ")";
                return denyVelocity(budget, request, delegatedToken, cutoff, desc);
            }
        }

        if (budget.getVelocityMaxPerDay() != null) {
            OffsetDateTime cutoff = now.minusDays(1);
            long count = spendEventRepository.countAttemptsAfter(budget.getId(), cutoff);
            if (count >= budget.getVelocityMaxPerDay()) {
                String desc = "maxPerDay=" + budget.getVelocityMaxPerDay()
                    + " (window count=" + count + ")";
                return denyVelocity(budget, request, delegatedToken, cutoff, desc);
            }
        }

        return null;
    }

    /**
     * Handles a velocity limit violation.
     *
     * <ul>
     *   <li>Dry-run: returns a phantom DENIED event without writing or firing webhooks.</li>
     *   <li>First violation in the window: saves a VELOCITY_LIMIT_EXCEEDED event and fires
     *       the VELOCITY_LIMIT_EXCEEDED webhook.</li>
     *   <li>Subsequent violations in the same window: returns the first violation event
     *       silently (no new write, no webhook).</li>
     * </ul>
     *
     * @param dedupCutoff  start of the dedup window matching the violated limit
     * @param violatedLimit  human-readable description for the webhook payload
     */
    private AuthorizationResponse denyVelocity(AgentBudget budget,
                                                 AuthorizeSpendRequest request,
                                                 DelegatedToken delegatedToken,
                                                 OffsetDateTime dedupCutoff,
                                                 String violatedLimit) {
        SpendEvent parentEvent = resolveParentForDenial(request, budget.getId());

        // Dry-run: phantom event, no write, no webhook
        if (request.isDryRun()) {
            SpendEvent phantom = buildEvent(budget, null, request, parentEvent,
                SpendDecision.DENIED, DenialCode.VELOCITY_LIMIT_EXCEEDED.name(),
                "Velocity limit violated: " + violatedLimit, null, delegatedToken);
            log.info("DRY_RUN VELOCITY_DENIED: budgetId={} limit={}", budget.getId(), violatedLimit);
            return buildResponse(phantom, budget, null);
        }

        // Dedup check: if a VELOCITY_LIMIT_EXCEEDED event already exists in this window,
        // return it silently — no new write, no webhook re-fire.
        List<SpendEvent> existing = spendEventRepository
            .findVelocityDenialAfter(budget.getId(), dedupCutoff);
        if (!existing.isEmpty()) {
            SpendEvent first = existing.get(0);
            log.info("VELOCITY_DENIED (dedup): budgetId={} existingEventId={} limit={}",
                budget.getId(), first.getId(), violatedLimit);
            return buildResponse(first, budget, null);
        }

        // First violation in this window: write the event and fire the webhook.
        SpendEvent event = buildEvent(budget, null, request, parentEvent,
            SpendDecision.DENIED, DenialCode.VELOCITY_LIMIT_EXCEEDED.name(),
            "Velocity limit violated: " + violatedLimit, null, delegatedToken);
        SpendEvent saved = spendEventRepository.save(event);

        // Root velocity denials (no parent) self-reference chainRootEventId.
        if (parentEvent == null) {
            saved.setChainRootEventId(saved.getId());
            saved = spendEventRepository.save(saved);
        }

        log.warn("VELOCITY_LIMIT_EXCEEDED: budgetId={} limit={} key={}",
            budget.getId(), violatedLimit, request.getIdempotencyKey());

        Counter.builder("figuard.velocity.denied")
            .register(meterRegistry)
            .increment();

        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.VELOCITY_LIMIT_EXCEEDED,
            webhookPayloadBuilder.buildVelocityLimitExceededPayload(budget, saved, violatedLimit));

        return buildResponse(saved, budget, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Soft parent lookup used by early denial paths (velocity, anomaly) that fire before
     * Step 8 parent validation. Returns null silently if the ID is absent, the event
     * doesn't exist, or belongs to a different budget — the denial is recorded regardless,
     * but with a parent link when one can be resolved.
     */
    private SpendEvent resolveParentForDenial(AuthorizeSpendRequest request, UUID budgetId) {
        if (request.getParentEventId() == null) return null;
        return spendEventRepository.findById(request.getParentEventId())
            .filter(p -> p.getBudget().getId().equals(budgetId))
            .orElse(null);
    }

    private SpendEvent validateParentEvent(UUID parentEventId, UUID budgetId) {
        SpendEvent parent = spendEventRepository.findById(parentEventId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, DenialCode.INVALID_PARENT_EVENT.name()));

        if (!parent.getBudget().getId().equals(budgetId)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, DenialCode.INVALID_PARENT_EVENT.name());
        }

        SpendDecision d = parent.getDecision();
        if (d != SpendDecision.AUTHORIZED && d != SpendDecision.CONFIRMED) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                DenialCode.INVALID_PARENT_EVENT.name() + ": parent event is in terminal state " + d);
        }

        return parent;
    }

    private SpendEvent buildEvent(AgentBudget budget,
                                  BudgetAllocation allocation,
                                  AuthorizeSpendRequest request,
                                  SpendEvent parentEvent,
                                  SpendDecision decision,
                                  String denialReason,
                                  String denialMessage,
                                  String failureReason,
                                  DelegatedToken delegatedToken) {
        SpendEvent event = new SpendEvent();
        event.setBudget(budget);
        event.setTenant(budget.getTenant());
        event.setRootBudgetId(budget.getId());
        event.setAllocation(allocation);
        event.setParentEvent(parentEvent);
        event.setAgentId(request.getAgentId());
        event.setAgentType(request.getAgentType());
        event.setActionType(request.getActionType());
        event.setDescription(request.getDescription());
        event.setRequestedQuantity(request.getRequestedQuantity());
        event.setCurrency(budget.getCurrency());
        event.setEntityId(request.getEntityId());
        event.setClaimedCategory(request.getClaimedCategory());
        event.setClaimedItemType(request.getClaimedItemType());
        event.setIntentContext(request.getIntentContext());
        event.setIdempotencyKey(request.getIdempotencyKey());
        event.setTraceId(request.getTraceId());
        event.setDecision(decision);
        event.setDenialReason(denialReason);
        event.setDenialMessage(denialMessage);
        event.setFailureReason(failureReason);
        event.setMetadata(request.getMetadata());
        if (delegatedToken != null) {
            event.setDelegatedTokenId(delegatedToken.getId());
        }

        // Causal chain tracking — child events can be assigned now; root events need a
        // two-step save because the ID isn't available until after the first persist.
        // Root events are patched in the callers (approve/deny) after the first save.
        if (parentEvent != null) {
            event.setChainRootEventId(parentEvent.getChainRootEventId());
        }
        if (parentEvent == null && request.getMaxSubtreeQuantity() != null) {
            event.setMaxSubtreeQuantity(request.getMaxSubtreeQuantity());
        }

        return event;
    }

    // -------------------------------------------------------------------------
    // Threshold webhook dispatch
    // -------------------------------------------------------------------------

    /**
     * Fires BUDGET_50_PCT, BUDGET_90_PCT, or BUDGET_EXHAUSTED webhooks when a
     * reservation crosses a threshold downward.
     *
     * Only fires when the threshold is crossed by THIS specific authorization —
     * prev > threshold and new <= threshold — to prevent re-firing on every
     * subsequent request while the balance remains below the threshold.
     */
    private void dispatchThresholdWebhooks(AgentBudget budget, BigDecimal prevAvailable) {
        BigDecimal totalLimit = budget.getTotalLimit();
        if (totalLimit.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal newAvailable = budget.availableQuantity();

        BigDecimal tenPct    = totalLimit.multiply(new BigDecimal("0.10"));
        BigDecimal fiftyPct  = totalLimit.multiply(new BigDecimal("0.50"));

        UUID tenantId = budget.getTenant().getId();

        if (newAvailable.compareTo(BigDecimal.ZERO) == 0) {
            webhookDispatcher.dispatch(tenantId, WebhookEventType.BUDGET_EXHAUSTED,
                webhookPayloadBuilder.buildThresholdPayload(WebhookEventType.BUDGET_EXHAUSTED, budget));
        } else if (prevAvailable.compareTo(tenPct) > 0 && newAvailable.compareTo(tenPct) <= 0) {
            webhookDispatcher.dispatch(tenantId, WebhookEventType.BUDGET_90_PCT,
                webhookPayloadBuilder.buildThresholdPayload(WebhookEventType.BUDGET_90_PCT, budget));
        } else if (prevAvailable.compareTo(fiftyPct) > 0 && newAvailable.compareTo(fiftyPct) <= 0) {
            webhookDispatcher.dispatch(tenantId, WebhookEventType.BUDGET_50_PCT,
                webhookPayloadBuilder.buildThresholdPayload(WebhookEventType.BUDGET_50_PCT, budget));
        }
    }

    private AuthorizationResponse buildResponse(SpendEvent event,
                                                 AgentBudget budget,
                                                 BudgetAllocation allocation) {
        return buildResponse(event, budget, allocation, null);
    }

    private AuthorizationResponse buildResponse(SpendEvent event,
                                                 AgentBudget budget,
                                                 BudgetAllocation allocation,
                                                 UUID originalEventId) {
        BudgetSnapshot budgetSnapshot = budgetMapper.toBudgetSnapshot(budget);
        AllocationSnapshot allocationSnapshot = allocation != null
            ? budgetMapper.toAllocationSnapshot(allocation) : null;

        DenialCode denialCode = event.getDenialReason() != null
            ? DenialCode.valueOf(event.getDenialReason()) : null;

        return AuthorizationResponse.builder()
            .eventId(event.getId())
            .decision(event.getDecision())
            .approvedQuantity(event.getDecision() == SpendDecision.AUTHORIZED
                ? event.getRequestedQuantity() : null)
            .authorizedAt(event.getDecision() == SpendDecision.AUTHORIZED
                ? event.getCreatedAt() : null)
            .denialReason(denialCode)
            .denialMessage(event.getDenialMessage())
            .allocationSnapshot(allocationSnapshot)
            .originalEventId(originalEventId)
            .budgetSnapshot(budgetSnapshot)
            .traceId(MDC.get(TraceIdFilter.TRACE_ID_KEY))
            .build();
    }
}
