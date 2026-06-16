package com.figuard.service;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.RecordExternalEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.DelegatedTokenAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.DelegatedTokenAllocationRepository;
import com.figuard.domain.repository.SpendEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.figuard.api.dto.request.VoidTreeRequest;
import com.figuard.api.dto.response.VoidTreeResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLifecycleService {

    private final SpendEventRepository eventRepository;
    private final AgentBudgetRepository budgetRepository;
    private final BudgetAllocationRepository allocationRepository;
    private final DelegatedTokenAllocationRepository delegatedTokenAllocationRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final BudgetMapper budgetMapper;
    private final AnomalyBaselineService anomalyBaselineService;
    private final EntitlementEnforcementService entitlementEnforcementService;
    private final MeterRegistry meterRegistry;

    // -------------------------------------------------------------------------
    // Confirm
    // -------------------------------------------------------------------------

    @Transactional
    public SpendEventResponse confirmEvent(UUID eventId, ConfirmEventRequest request, Tenant tenant) {
        SpendEvent event = loadEventWithLock(eventId, tenant);

        if (!event.canBeConfirmed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Event is not in AUTHORIZED state (current: " + event.getDecision() + ")");
        }

        if (event.getConfirmationTimeoutAt() != null
                && event.getConfirmationTimeoutAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUTHORIZATION_EXPIRED");
        }

        BigDecimal reserved   = event.getRequestedQuantity();
        BigDecimal confirmed  = request.getConfirmedQuantity();

        // Update budget: reserved → spent. A reserve=false event held no reservation, so only
        // the confirmed actual is recorded as spend; quantityReserved is left untouched
        // (subtracting it would drive the counter negative). reserve=false is flat-only, so the
        // allocation/delegate paths below are not reached by such events.
        AgentBudget budget = loadBudgetWithLock(event);
        if (event.isReserved()) {
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
        }
        budget.setQuantitySpent(budget.getQuantitySpent().add(confirmed));
        budgetRepository.save(budget);

        // Update allocation (if any): reserved → spent
        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            alloc.setQuantitySpent(alloc.getQuantitySpent().add(confirmed));
            allocationRepository.save(alloc);
        }

        // Update delegation token allocation (if any): reserved → spent
        if (event.getDelegatedTokenId() != null && event.getAllocation() != null) {
            delegatedTokenAllocationRepository
                .findByTokenIdAndCategoryWithLock(event.getDelegatedTokenId(),
                    event.getAllocation().getCategory())
                .ifPresent(delegateAlloc -> {
                    delegateAlloc.setQuantityReserved(delegateAlloc.getQuantityReserved().subtract(reserved));
                    delegateAlloc.setQuantitySpent(delegateAlloc.getQuantitySpent().add(confirmed));
                    delegatedTokenAllocationRepository.save(delegateAlloc);
                });
        }

        event.setDecision(SpendDecision.CONFIRMED);
        event.setConfirmedQuantity(confirmed);
        event.setExternalTransactionId(request.getExternalTransactionId());
        eventRepository.save(event);

        // Adjust entitlement consumed if partial confirmation (confirmed < reserved)
        if (event.getEntitlementItemId() != null) {
            entitlementEnforcementService.adjust(event.getEntitlementItemId(), reserved, confirmed);
        }

        log.info("Event CONFIRMED: id={} budgetId={} confirmed={}",
            event.getId(), budget.getId(), confirmed);

        Counter.builder("figuard.event.confirmed").register(meterRegistry).increment();

        // Update anomaly baseline asynchronously — must not delay the confirm response
        anomalyBaselineService.updateBaseline(budget, confirmed);

        // Fire SPEND_CONFIRMED webhook asynchronously
        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_CONFIRMED,
            webhookPayloadBuilder.buildSpendConfirmedPayload(budget, event));

        return budgetMapper.toResponse(event);
    }

    // -------------------------------------------------------------------------
    // Fail
    // -------------------------------------------------------------------------

    @Transactional
    public SpendEventResponse failEvent(UUID eventId, FailEventRequest request, Tenant tenant) {
        SpendEvent event = loadEventWithLock(eventId, tenant);

        if (!event.canBeFailed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Event is not in AUTHORIZED state (current: " + event.getDecision() + ")");
        }

        BigDecimal reserved = event.getRequestedQuantity();

        // Release reservation on budget — only if one was actually held (reserve=false held none).
        AgentBudget budget = loadBudgetWithLock(event);
        if (event.isReserved()) {
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
            budgetRepository.save(budget);
        }

        // Release reservation on allocation (if any)
        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            allocationRepository.save(alloc);
        }

        // Release reservation on delegation token allocation (if any)
        if (event.getDelegatedTokenId() != null && event.getAllocation() != null) {
            delegatedTokenAllocationRepository
                .findByTokenIdAndCategoryWithLock(event.getDelegatedTokenId(),
                    event.getAllocation().getCategory())
                .ifPresent(delegateAlloc -> {
                    delegateAlloc.setQuantityReserved(
                        delegateAlloc.getQuantityReserved().subtract(reserved));
                    delegatedTokenAllocationRepository.save(delegateAlloc);
                });
        }

        event.setDecision(SpendDecision.FAILED);
        event.setFailureReason(request.getReason());
        eventRepository.save(event);

        // Release entitlement reservation on failure
        if (event.getEntitlementItemId() != null) {
            entitlementEnforcementService.release(event.getEntitlementItemId(), reserved);
        }

        log.info("Event FAILED: id={} budgetId={} reason={}", event.getId(), budget.getId(), request.getReason());

        Counter.builder("figuard.event.failed").register(meterRegistry).increment();

        // Fire SPEND_PAYMENT_FAILED webhook asynchronously
        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_PAYMENT_FAILED,
            webhookPayloadBuilder.buildSpendPaymentFailedPayload(budget, event));

        return budgetMapper.toResponse(event);
    }

    // -------------------------------------------------------------------------
    // Void
    // -------------------------------------------------------------------------

    @Transactional
    public SpendEventResponse voidEvent(UUID eventId, VoidEventRequest request, Tenant tenant) {
        SpendEvent event = loadEventWithLock(eventId, tenant);

        if (!event.canBeVoided()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Event is not in AUTHORIZED state (current: " + event.getDecision() + ")");
        }

        if (event.getExternalTransactionId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "VOID_REQUIRES_REFUND: event has externalTransactionId="
                    + event.getExternalTransactionId());
        }

        // Collect all AUTHORIZED descendants (recursive, depth-first)
        List<SpendEvent> authorizedDescendants = collectVoidableDescendants(eventId);

        if (!request.isVoidChildEvents() && !authorizedDescendants.isEmpty()) {
            // Guard: cannot void a parent while children hold live reservations.
            // The caller must explicitly opt in to cascade or void children first.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Event has " + authorizedDescendants.size()
                    + " authorized descendant(s). Void them first or pass voidChildEvents=true to cascade.");
        }

        AgentBudget budget = loadBudgetWithLock(event);

        if (request.isVoidChildEvents()) {
            // Validate all descendants upfront — fail atomically if any have external transactions
            for (SpendEvent desc : authorizedDescendants) {
                if (desc.getExternalTransactionId() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "VOID_REQUIRES_REFUND: descendant event " + desc.getId()
                            + " has externalTransactionId=" + desc.getExternalTransactionId());
                }
            }
            // Void descendants deepest-first so budget arithmetic stays consistent
            for (int i = authorizedDescendants.size() - 1; i >= 0; i--) {
                voidSingleEventWithRelease(authorizedDescendants.get(i), request.getReason(), budget);
            }
        }

        BigDecimal reserved = event.getRequestedQuantity();
        // reserve=false held no reservation, so there is nothing to release on void.
        if (event.isReserved()) {
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
            budgetRepository.save(budget);
        }

        if (event.isReserved() && event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            allocationRepository.save(alloc);
        }

        // Release reservation on delegation token allocation (if any)
        if (event.getDelegatedTokenId() != null && event.getAllocation() != null) {
            delegatedTokenAllocationRepository
                .findByTokenIdAndCategoryWithLock(event.getDelegatedTokenId(),
                    event.getAllocation().getCategory())
                .ifPresent(delegateAlloc -> {
                    delegateAlloc.setQuantityReserved(
                        delegateAlloc.getQuantityReserved().subtract(reserved));
                    delegatedTokenAllocationRepository.save(delegateAlloc);
                });
        }

        event.setDecision(SpendDecision.VOIDED);
        event.setFailureReason(request.getReason());
        eventRepository.save(event);

        // Release entitlement reservation on void
        if (event.getEntitlementItemId() != null) {
            entitlementEnforcementService.release(event.getEntitlementItemId(), reserved);
        }

        log.info("Event VOIDED: id={} budgetId={} descendants={} reason={}",
            event.getId(), budget.getId(),
            request.isVoidChildEvents() ? authorizedDescendants.size() : 0,
            request.getReason());

        Counter.builder("figuard.event.voided").register(meterRegistry).increment();

        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_VOIDED,
            webhookPayloadBuilder.buildSpendVoidedPayload(budget, event));

        return budgetMapper.toResponse(event);
    }

    // -------------------------------------------------------------------------
    // Void tree (cascading void)
    // -------------------------------------------------------------------------

    /**
     * Atomically void an entire causal subtree rooted at {@code rootEventId}.
     *
     * <p>Finds every AUTHORIZED descendant via the parentEventId chain, validates none
     * carry an externalTransactionId (which would require a refund first), then voids
     * them all deepest-first in a single transaction before voiding the root.
     *
     * <p>CONFIRMED, DENIED, and already-VOIDED descendants are left untouched — only
     * live AUTHORIZED reservations are released.
     *
     * @return a summary of every event voided and the total quantity released back to the budget.
     */
    @Transactional
    public VoidTreeResponse voidTree(UUID rootEventId, VoidTreeRequest request, Tenant tenant) {
        SpendEvent root = loadEventWithLock(rootEventId, tenant);

        if (!root.canBeVoided()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Root event cannot be voided (current: " + root.getDecision() + ")");
        }

        if (root.getExternalTransactionId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "VOID_REQUIRES_REFUND: root event has externalTransactionId="
                    + root.getExternalTransactionId());
        }

        // Collect all voidable (AUTHORIZED or CONFIRMED) descendants — BFS order, root not included
        List<SpendEvent> descendants = collectVoidableDescendants(rootEventId);

        // Validate all descendants upfront — fail atomically if any require a refund
        for (SpendEvent desc : descendants) {
            if (desc.getExternalTransactionId() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "VOID_REQUIRES_REFUND: descendant event " + desc.getId()
                        + " has externalTransactionId=" + desc.getExternalTransactionId());
            }
        }

        AgentBudget budget = loadBudgetWithLock(root);

        // Void descendants deepest-first so budget arithmetic is consistent
        // (children release their reservations before the parent does)
        for (int i = descendants.size() - 1; i >= 0; i--) {
            voidSingleEventWithRelease(descendants.get(i), request.getReason(), budget);
        }

        // Void the root — arithmetic depends on whether it was AUTHORIZED or CONFIRMED
        BigDecimal rootQty = root.getDecision() == SpendDecision.CONFIRMED
            ? root.getConfirmedQuantity()    // confirmed: undo quantitySpent
            : root.getRequestedQuantity();   // authorized: undo quantityReserved

        if (root.getDecision() == SpendDecision.CONFIRMED) {
            budget.setQuantitySpent(budget.getQuantitySpent().subtract(rootQty));
        } else {
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(rootQty));
        }
        budgetRepository.save(budget);

        if (root.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(root.getAllocation().getId());
            if (root.getDecision() == SpendDecision.CONFIRMED) {
                alloc.setQuantitySpent(alloc.getQuantitySpent().subtract(rootQty));
            } else {
                alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(rootQty));
            }
            allocationRepository.save(alloc);
        }

        if (root.getDelegatedTokenId() != null && root.getAllocation() != null) {
            boolean confirmed = root.getDecision() == SpendDecision.CONFIRMED;
            delegatedTokenAllocationRepository
                .findByTokenIdAndCategoryWithLock(root.getDelegatedTokenId(),
                    root.getAllocation().getCategory())
                .ifPresent(delegateAlloc -> {
                    if (confirmed) {
                        delegateAlloc.setQuantitySpent(delegateAlloc.getQuantitySpent().subtract(rootQty));
                    } else {
                        delegateAlloc.setQuantityReserved(delegateAlloc.getQuantityReserved().subtract(rootQty));
                    }
                    delegatedTokenAllocationRepository.save(delegateAlloc);
                });
        }

        root.setDecision(SpendDecision.VOIDED);
        root.setFailureReason(request.getReason());
        eventRepository.save(root);

        if (root.getEntitlementItemId() != null) {
            entitlementEnforcementService.release(root.getEntitlementItemId(), rootQty);
        }

        // Build the summary
        int totalVoided = 1 + descendants.size();
        BigDecimal totalReleased = descendants.stream()
            .map(e -> e.getDecision() == SpendDecision.CONFIRMED ? e.getConfirmedQuantity() : e.getRequestedQuantity())
            .reduce(rootQty, BigDecimal::add);

        List<UUID> voidedIds = new ArrayList<>();
        voidedIds.add(root.getId());
        descendants.stream().map(SpendEvent::getId).forEach(voidedIds::add);

        log.info("Tree VOIDED: rootId={} budgetId={} totalVoided={} totalReleased={} reason={}",
            root.getId(), budget.getId(), totalVoided, totalReleased, request.getReason());

        Counter.builder("figuard.event.tree_voided")
            .tag("voided_count", String.valueOf(totalVoided))
            .register(meterRegistry).increment();

        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_TREE_VOIDED,
            webhookPayloadBuilder.buildSpendTreeVoidedPayload(
                budget, root, totalVoided, totalReleased, voidedIds));

        return VoidTreeResponse.builder()
            .rootEventId(root.getId())
            .voidedCount(totalVoided)
            .totalQuantityReleased(totalReleased)
            .currency(root.getCurrency())
            .voidedEventIds(voidedIds)
            .reason(request.getReason())
            .build();
    }

    /**
     * Recursively collect all voidable (AUTHORIZED or CONFIRMED) descendants of a given
     * event ID, in BFS order. DENIED and already-VOIDED children are skipped.
     */
    private List<SpendEvent> collectVoidableDescendants(UUID parentId) {
        List<SpendEvent> result = new ArrayList<>();
        List<SpendEvent> directChildren = eventRepository.findByParentEventId(parentId);
        for (SpendEvent child : directChildren) {
            if (child.getDecision() == SpendDecision.AUTHORIZED
                    || child.getDecision() == SpendDecision.CONFIRMED) {
                result.add(child);
                result.addAll(collectVoidableDescendants(child.getId()));
            }
        }
        return result;
    }

    /**
     * Void a single descendant event and release its budget/allocation reservation inline.
     * The caller must save the budget after the loop to batch writes.
     */
    private void voidSingleEventWithRelease(SpendEvent event, String reason, AgentBudget budget) {
        // Arithmetic depends on whether the event was AUTHORIZED (reserved) or CONFIRMED (spent)
        boolean wasConfirmed = event.getDecision() == SpendDecision.CONFIRMED;
        BigDecimal qty = wasConfirmed ? event.getConfirmedQuantity() : event.getRequestedQuantity();

        if (wasConfirmed) {
            budget.setQuantitySpent(budget.getQuantitySpent().subtract(qty));
        } else {
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(qty));
        }

        // Release from the allocation if applicable
        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            if (wasConfirmed) {
                alloc.setQuantitySpent(alloc.getQuantitySpent().subtract(qty));
            } else {
                alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(qty));
            }
            allocationRepository.save(alloc);
        }

        // Release on delegation token allocation (if any)
        if (event.getDelegatedTokenId() != null && event.getAllocation() != null) {
            delegatedTokenAllocationRepository
                .findByTokenIdAndCategoryWithLock(event.getDelegatedTokenId(),
                    event.getAllocation().getCategory())
                .ifPresent(delegateAlloc -> {
                    if (wasConfirmed) {
                        delegateAlloc.setQuantitySpent(delegateAlloc.getQuantitySpent().subtract(qty));
                    } else {
                        delegateAlloc.setQuantityReserved(
                            delegateAlloc.getQuantityReserved().subtract(qty));
                    }
                    delegatedTokenAllocationRepository.save(delegateAlloc);
                });
        }

        event.setDecision(SpendDecision.VOIDED);
        event.setFailureReason(reason);
        eventRepository.save(event);

        // Release entitlement for this event
        if (event.getEntitlementItemId() != null) {
            entitlementEnforcementService.release(event.getEntitlementItemId(), qty);
        }
    }

    // -------------------------------------------------------------------------
    // External events
    // -------------------------------------------------------------------------

    /**
     * Record a spend that happened outside the normal authorize → confirm flow.
     *
     * <p>Creates a SpendEvent directly in CONFIRMED state and charges the amount
     * against the budget's quantitySpent. No reservation is created — the money
     * was already spent. Budget capacity limits are intentionally NOT enforced
     * (the action already occurred in the external system).
     *
     * <p>Idempotency: the idempotencyKey uniqueness constraint on the spend_events
     * table prevents duplicate recording. A second call with the same key returns HTTP 409.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Finance manager processes an emergency payment directly in QuickBooks</li>
     *   <li>A third-party system completes a charge and you need to keep FiGuard's ledger in sync</li>
     *   <li>End-of-day batch reconciliation from an external payment processor</li>
     * </ul>
     */
    @Transactional
    public SpendEventResponse recordExternalEvent(RecordExternalEventRequest request, Tenant tenant) {
        AgentBudget budget = budgetRepository.findByIdWithLock(request.getBudgetId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found");
        }

        BigDecimal quantity = request.getQuantity();
        String source = (request.getSource() != null && !request.getSource().isBlank())
            ? request.getSource().toUpperCase()
            : "EXTERNAL";

        SpendEvent event = new SpendEvent();
        event.setTenant(tenant);
        event.setBudget(budget);
        event.setRootBudgetId(budget.getId());
        event.setAgentId(request.getAgentId());
        event.setAgentType(source);         // repurpose agentType for source label
        event.setActionType(request.getActionType());
        event.setDescription(request.getDescription());
        event.setRequestedQuantity(quantity);
        event.setConfirmedQuantity(quantity);
        event.setCurrency(budget.getCurrency());
        event.setClaimedCategory(request.getClaimedCategory());
        event.setIdempotencyKey(request.getIdempotencyKey());
        event.setDecision(SpendDecision.CONFIRMED);
        event.setEventSource(source);
        event.setOccurredAt(request.getOccurredAt() != null
            ? request.getOccurredAt()
            : java.time.OffsetDateTime.now());
        event.setMetadata(request.getMetadata());
        // chainRootEventId intentionally null — external events are not part of an
        // agent causal chain; subtree cap checks skip null chainRootEventId (V23 semantics).

        // Charge directly to quantitySpent — no reservation step
        budget.setQuantitySpent(budget.getQuantitySpent().add(quantity));
        budgetRepository.save(budget);

        eventRepository.save(event);

        log.info("External event recorded: id={} budgetId={} source={} quantity={}",
            event.getId(), budget.getId(), source, quantity);

        Counter.builder("figuard.event.external").register(meterRegistry).increment();

        webhookDispatcher.dispatch(
            tenant.getId(),
            WebhookEventType.SPEND_CONFIRMED,
            webhookPayloadBuilder.buildSpendConfirmedPayload(budget, event));

        return budgetMapper.toResponse(event);
    }

    // -------------------------------------------------------------------------
    // Sweep helper — called from ConfirmationTimeoutSweepService
    // -------------------------------------------------------------------------

    @Transactional
    public void autoVoidStaleEvent(SpendEvent stale) {
        // Re-fetch with lock inside this transaction to prevent concurrent void races
        eventRepository.findByIdWithLock(stale.getId()).ifPresent(event -> {
            if (event.getDecision() != SpendDecision.AUTHORIZED) return;  // only void pending reservations

            BigDecimal reserved = event.getRequestedQuantity();

            AgentBudget budget = loadBudgetWithLock(event);
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
            budgetRepository.save(budget);

            if (event.getAllocation() != null) {
                BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
                alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
                allocationRepository.save(alloc);
            }

            if (event.getDelegatedTokenId() != null && event.getAllocation() != null) {
                delegatedTokenAllocationRepository
                    .findByTokenIdAndCategoryWithLock(event.getDelegatedTokenId(),
                        event.getAllocation().getCategory())
                    .ifPresent(delegateAlloc -> {
                        delegateAlloc.setQuantityReserved(
                            delegateAlloc.getQuantityReserved().subtract(reserved));
                        delegatedTokenAllocationRepository.save(delegateAlloc);
                    });
            }

            event.setDecision(SpendDecision.VOIDED);
            event.setFailureReason("CONFIRMATION_TIMEOUT");
            eventRepository.save(event);

            // Release entitlement reservation on auto-void
            if (event.getEntitlementItemId() != null) {
                entitlementEnforcementService.release(event.getEntitlementItemId(), reserved);
            }

            log.info("Event auto-voided (timeout): id={} budgetId={} timeoutAt={}",
                event.getId(), budget.getId(), event.getConfirmationTimeoutAt());
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SpendEvent loadEventWithLock(UUID eventId, Tenant tenant) {
        SpendEvent event = eventRepository.findByIdWithLock(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        if (!event.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return event;
    }

    private AgentBudget loadBudgetWithLock(SpendEvent event) {
        return budgetRepository.findByIdWithLock(event.getBudget().getId())
            .orElseThrow(() -> new IllegalStateException("Budget missing for event " + event.getId()));
    }

    private BudgetAllocation loadAllocationWithLock(UUID allocationId) {
        return allocationRepository.findByIdWithLock(allocationId)
            .orElseThrow(() -> new IllegalStateException("Allocation missing: " + allocationId));
    }

}
