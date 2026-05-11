package com.figuard.service;

import com.figuard.api.dto.request.AuthorizeSpendRequest;
import com.figuard.api.dto.response.AllocationSnapshot;
import com.figuard.api.dto.response.AuthorizationResponse;
import com.figuard.api.dto.response.BudgetSnapshot;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.service.model.MatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
        // Checks both the current hash and the previous hash (within rotation grace window),
        // so in-flight agents using the old token are not dropped mid-task.
        String tokenHash = sessionTokenService.hashToken(rawSessionToken);
        AgentBudget budget = budgetRepository
            .findBySessionTokenHashOrPrevious(tokenHash, OffsetDateTime.now())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, DenialCode.INVALID_SESSION_TOKEN.name()));

        // Step 2 — Idempotency check (skipped for dry-run — nothing was ever written)
        // Must happen before any writes. If a duplicate key is found, return the original
        // decision without creating a new SpendEvent. This handles agent retries safely.
        if (!request.isDryRun()) {
            var existingEvent = spendEventRepository
                .findByBudgetIdAndIdempotencyKey(budget.getId(), request.getIdempotencyKey());
            if (existingEvent.isPresent()) {
                SpendEvent cached = existingEvent.get();
                log.info("Idempotent hit: budgetId={} key={} decision={}",
                    budget.getId(), request.getIdempotencyKey(), cached.getDecision());
                return buildResponse(cached, budget, null);
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
                    "entityId '" + request.getEntityId() + "' already has a live event on this budget", null);
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
            case PAUSED    -> { return deny(budget, null, request, null, DenialCode.BUDGET_PAUSED,    "Budget is paused"); }
            case CANCELLED -> { return deny(budget, null, request, null, DenialCode.BUDGET_CANCELLED, "Budget has been cancelled"); }
            case EXHAUSTED -> { return deny(budget, null, request, null, DenialCode.BUDGET_EXHAUSTED, "Budget is exhausted"); }
            default -> { /* ACTIVE — continue */ }
        }

        // Step 5 — Expiry check with grace buffer
        // A 60-second grace window prevents race conditions where the budget expires
        // between the agent checking and sending the request.
        OffsetDateTime effectiveExpiry = budget.getExpiresAt().plusSeconds(expiryGraceSeconds);
        if (OffsetDateTime.now().isAfter(effectiveExpiry)) {
            return deny(budget, null, request, null, DenialCode.BUDGET_EXPIRED, "Budget has expired");
        }

        // Step 6 — Currency check (monetary budgets only)
        // Resource budgets (unit set, currency null) skip this check entirely.
        if (budget.isMonetary()) {
            String requestCurrency = request.getCurrency() != null ? request.getCurrency() : "USD";
            String budgetCurrency  = budget.getCurrency().trim();
            if (!requestCurrency.equalsIgnoreCase(budgetCurrency)) {
                return deny(budget, null, request, null, DenialCode.CURRENCY_MISMATCH,
                    "Currency mismatch: requested " + requestCurrency + " but budget is " + budgetCurrency);
            }
        }

        // Step 7 — Per-transaction ceiling check
        // Only enforced when maxTransactionQuantity is set on the budget.
        // Checked before funds availability — a request that exceeds the ceiling is denied
        // even if the budget has plenty of remaining funds.
        if (budget.getMaxTransactionQuantity() != null
                && request.getRequestedQuantity().compareTo(budget.getMaxTransactionQuantity()) > 0) {
            return deny(budget, null, request, null, DenialCode.EXCEEDS_QUANTITY_LIMIT,
                "requestedQuantity " + request.getRequestedQuantity() +
                " exceeds per-transaction limit of " + budget.getMaxTransactionQuantity());
        }

        // Step 7a — Anomaly detection
        // Only runs when anomalyDetectionEnabled=true and the baseline has enough samples
        // (anomalyMinSampleSize guard prevents false positives on brand-new budgets).
        if (budget.isAnomalyDetectionEnabled()) {
            var anomalyResult = checkAnomaly(budget, request.getRequestedQuantity(), request);
            if (anomalyResult != null) return anomalyResult;
        }

        // Step 8 — Parent event validation (causal chain)
        SpendEvent parentEvent = null;
        if (request.getParentEventId() != null) {
            parentEvent = validateParentEvent(request.getParentEventId(), budget.getId());
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
        if (!allocations.isEmpty()) {
            return authorizeWithAllocations(budget, allocations, request, parentEvent, effectiveReserved);
        } else {
            return authorizeFlat(budget, request, parentEvent, effectiveReserved);
        }
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
                                                            BigDecimal effectiveReserved) {
        MatchResult matchResult = categoryMatchingService.findMatch(
            allocations, request.getClaimedCategory(), request.getClaimedItemType());

        return switch (matchResult) {
            case MatchResult.MissingCategory ignored ->
                // Missing claimedCategory is returned as a structured DENIED rather than a 400.
                // LLMs and agents handle a DENIED decision cleanly; a raw 400 gives them nothing
                // to reason about. The event is recorded so the audit trail is complete.
                deny(budget, null, request, parentEvent, DenialCode.MISSING_CLAIMED_CATEGORY,
                    "This budget has allocations — claimedCategory is required");

            case MatchResult.NoMatch ignored ->
                deny(budget, null, request, parentEvent, DenialCode.NO_MATCHING_ALLOCATION,
                    "claimedCategory '" + request.getClaimedCategory() + "' does not match any allocation");

            case MatchResult.Forbidden f ->
                deny(budget, f.allocation(), request, parentEvent, DenialCode.FORBIDDEN_ITEM_TYPE,
                    "claimedItemType '" + f.itemType() + "' is forbidden on this allocation");

            case MatchResult.Match m -> {
                BudgetAllocation matched = m.allocation();

                // Re-fetch the matched allocation with a pessimistic write lock.
                // This prevents two concurrent threads from both reading the same
                // available balance and both thinking they can proceed.
                BudgetAllocation lockedAllocation = allocationRepository
                    .findByIdWithLock(matched.getId())
                    .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Allocation disappeared under lock"));

                if (!lockedAllocation.canAccommodate(request.getRequestedQuantity())) {
                    yield deny(budget, lockedAllocation, request, parentEvent, DenialCode.ALLOCATION_EXHAUSTED,
                        "Allocation '" + lockedAllocation.getCategory() + "' has " +
                        lockedAllocation.availableQuantity() + " available, requested " + request.getRequestedQuantity());
                }

                if (!budget.canAccommodateWith(request.getRequestedQuantity(), effectiveReserved)) {
                    yield deny(budget, lockedAllocation, request, parentEvent, DenialCode.INSUFFICIENT_FUNDS,
                        "Budget has " + budget.availableQuantityWith(effectiveReserved) + " available, requested " + request.getRequestedQuantity());
                }

                yield approve(budget, lockedAllocation, request, parentEvent);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Step 10b — Flat budget path (no allocations)
    // -------------------------------------------------------------------------

    private AuthorizationResponse authorizeFlat(AgentBudget budget, AuthorizeSpendRequest request,
                                                 SpendEvent parentEvent, BigDecimal effectiveReserved) {
        // Intent scope check — only on flat budgets. Allocated budgets enforce intent via
        // claimedCategory; this check would be redundant and is intentionally skipped there.
        if (!intentScopeValidator.isInScope(budget.getIntentTags(), request.getIntentContext())) {
            String message = (request.getIntentContext() == null || request.getIntentContext().isBlank())
                ? "This budget requires intentContext — budget intentTags: " +
                  java.util.Arrays.toString(budget.getIntentTags())
                : "intentContext '" + request.getIntentContext() +
                  "' does not match any budget intentTags: " +
                  java.util.Arrays.toString(budget.getIntentTags());
            return deny(budget, null, request, parentEvent, DenialCode.INTENT_SCOPE_VIOLATION, message);
        }

        if (!budget.canAccommodateWith(request.getRequestedQuantity(), effectiveReserved)) {
            return deny(budget, null, request, parentEvent, DenialCode.INSUFFICIENT_FUNDS,
                "Budget has " + budget.availableQuantityWith(effectiveReserved) + " available, requested " + request.getRequestedQuantity());
        }
        return approve(budget, null, request, parentEvent);
    }

    // -------------------------------------------------------------------------
    // Step 11 — Reserve funds and write AUTHORIZED event
    // -------------------------------------------------------------------------

    private AuthorizationResponse approve(AgentBudget budget,
                                          BudgetAllocation allocation,
                                          AuthorizeSpendRequest request,
                                          SpendEvent parentEvent) {
        OffsetDateTime now = OffsetDateTime.now();
        SpendEvent event = buildEvent(budget, allocation, request, parentEvent, SpendDecision.AUTHORIZED, null, null, null);
        event.setCreatedAt(now);
        event.setConfirmationTimeoutAt(now.plusSeconds(confirmationTimeoutSeconds));

        // Dry-run: return the authorization decision without writing, reserving funds,
        // or firing webhooks. All enforcement checks above have already run.
        if (request.isDryRun()) {
            log.info("DRY_RUN AUTHORIZED: budgetId={} alloc={} quantity={}",
                budget.getId(),
                allocation != null ? allocation.getCategory() : "flat",
                request.getRequestedQuantity());
            return buildResponse(event, budget, allocation);
        }

        // Capture available quantity before reservation to detect threshold crossings
        BigDecimal prevAvailable = budget.availableQuantity();

        // Reserve at allocation level first, then budget level
        if (allocation != null) {
            allocation.setQuantityReserved(
                allocation.getQuantityReserved().add(request.getRequestedQuantity()));
            allocationRepository.save(allocation);
        }

        budget.setQuantityReserved(
            budget.getQuantityReserved().add(request.getRequestedQuantity()));
        budgetRepository.save(budget);

        SpendEvent saved = spendEventRepository.save(event);

        log.info("AUTHORIZED: budgetId={} alloc={} quantity={} key={}",
            budget.getId(),
            allocation != null ? allocation.getCategory() : "flat",
            request.getRequestedQuantity(),
            request.getIdempotencyKey());

        // Dispatch threshold webhooks asynchronously — must not block the authorize response
        dispatchThresholdWebhooks(budget, prevAvailable);

        return buildResponse(saved, budget, allocation);
    }

    // -------------------------------------------------------------------------
    // Denial helper — every DENIED path writes a SpendEvent to the ledger
    // -------------------------------------------------------------------------

    private AuthorizationResponse deny(AgentBudget budget,
                                       BudgetAllocation allocation,
                                       AuthorizeSpendRequest request,
                                       SpendEvent parentEvent,
                                       DenialCode code,
                                       String message) {
        SpendEvent event = buildEvent(budget, allocation, request, parentEvent,
            SpendDecision.DENIED, code.name(), message, null);

        // Dry-run: return the denial decision without writing or firing webhooks.
        if (request.isDryRun()) {
            log.info("DRY_RUN DENIED: budgetId={} code={}", budget.getId(), code);
            return buildResponse(event, budget, allocation);
        }

        SpendEvent saved = spendEventRepository.save(event);

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
        SpendEvent event = buildEvent(budget, null, request, null,
            SpendDecision.DENIED, DenialCode.ANOMALY_DETECTED.name(),
            "requestedQuantity " + request.getRequestedQuantity()
                + " exceeds anomaly threshold of " + threshold
                + " (mean=" + baselineMean + ")", null);
        SpendEvent saved = spendEventRepository.save(event);

        // Pause the budget synchronously — same transaction, so the pause is
        // committed atomically with the denial record.
        budget.setStatus(com.figuard.domain.enums.BudgetStatus.PAUSED);
        budgetRepository.save(budget);

        log.warn("ANOMALY_DETECTED: budgetId={} requested={} threshold={} — budget paused",
            budget.getId(), request.getRequestedQuantity(), threshold);

        // Fire ANOMALY_DETECTED webhook (not SPEND_DENIED) asynchronously
        String webhookUrl = budget.getAnomalyAlertWebhookUrl();
        UUID tenantId = budget.getTenant().getId();
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
    // Helpers
    // -------------------------------------------------------------------------

    private SpendEvent validateParentEvent(UUID parentEventId, UUID budgetId) {
        SpendEvent parent = spendEventRepository.findById(parentEventId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, DenialCode.INVALID_PARENT_EVENT.name()));

        if (!parent.getBudget().getId().equals(budgetId)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, DenialCode.INVALID_PARENT_EVENT.name());
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
                                  String failureReason) {
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
            .build();
    }
}
