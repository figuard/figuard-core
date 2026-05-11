package com.figuard.service;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.SpendEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class PaymentLifecycleService {

    private final SpendEventRepository eventRepository;
    private final AgentBudgetRepository budgetRepository;
    private final BudgetAllocationRepository allocationRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final BudgetMapper budgetMapper;
    private final AnomalyBaselineService anomalyBaselineService;

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

        // Update budget: reserved → spent
        AgentBudget budget = loadBudgetWithLock(event);
        budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
        budget.setQuantitySpent(budget.getQuantitySpent().add(confirmed));
        budgetRepository.save(budget);

        // Update allocation (if any): reserved → spent
        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            alloc.setQuantitySpent(alloc.getQuantitySpent().add(confirmed));
            allocationRepository.save(alloc);
        }

        event.setDecision(SpendDecision.CONFIRMED);
        event.setConfirmedQuantity(confirmed);
        event.setExternalTransactionId(request.getExternalTransactionId());
        eventRepository.save(event);

        log.info("Event CONFIRMED: id={} budgetId={} confirmed={}",
            event.getId(), budget.getId(), confirmed);

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

        // Release reservation on budget
        AgentBudget budget = loadBudgetWithLock(event);
        budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
        budgetRepository.save(budget);

        // Release reservation on allocation (if any)
        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            allocationRepository.save(alloc);
        }

        event.setDecision(SpendDecision.FAILED);
        event.setFailureReason(request.getReason());
        eventRepository.save(event);

        log.info("Event FAILED: id={} budgetId={} reason={}", event.getId(), budget.getId(), request.getReason());

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

        if (request.isVoidChildEvents()) {
            List<SpendEvent> children = eventRepository.findByParentEventId(eventId);
            for (SpendEvent child : children) {
                if (child.getExternalTransactionId() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "VOID_REQUIRES_REFUND: child event " + child.getId()
                            + " has externalTransactionId=" + child.getExternalTransactionId());
                }
            }
            for (SpendEvent child : children) {
                if (child.canBeVoided()) {
                    voidSingleEvent(child, request.getReason());
                }
            }
        }

        BigDecimal reserved = event.getRequestedQuantity();

        AgentBudget budget = loadBudgetWithLock(event);
        budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
        budgetRepository.save(budget);

        if (event.getAllocation() != null) {
            BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
            alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
            allocationRepository.save(alloc);
        }

        event.setDecision(SpendDecision.VOIDED);
        event.setFailureReason(request.getReason());
        eventRepository.save(event);

        log.info("Event VOIDED: id={} budgetId={} reason={}", event.getId(), budget.getId(), request.getReason());

        webhookDispatcher.dispatch(
            budget.getTenant().getId(),
            WebhookEventType.SPEND_VOIDED,
            webhookPayloadBuilder.buildSpendVoidedPayload(budget, event));

        return budgetMapper.toResponse(event);
    }

    // -------------------------------------------------------------------------
    // Sweep helper — called from ConfirmationTimeoutSweepService
    // -------------------------------------------------------------------------

    @Transactional
    public void autoVoidStaleEvent(SpendEvent stale) {
        // Re-fetch with lock inside this transaction to prevent concurrent void races
        eventRepository.findByIdWithLock(stale.getId()).ifPresent(event -> {
            if (!event.canBeVoided()) return;  // already transitioned by another thread

            BigDecimal reserved = event.getRequestedQuantity();

            AgentBudget budget = loadBudgetWithLock(event);
            budget.setQuantityReserved(budget.getQuantityReserved().subtract(reserved));
            budgetRepository.save(budget);

            if (event.getAllocation() != null) {
                BudgetAllocation alloc = loadAllocationWithLock(event.getAllocation().getId());
                alloc.setQuantityReserved(alloc.getQuantityReserved().subtract(reserved));
                allocationRepository.save(alloc);
            }

            event.setDecision(SpendDecision.VOIDED);
            event.setFailureReason("CONFIRMATION_TIMEOUT");
            eventRepository.save(event);

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

    private void voidSingleEvent(SpendEvent event, String reason) {
        event.setDecision(SpendDecision.VOIDED);
        event.setFailureReason(reason);
        eventRepository.save(event);
        // Note: child events do not carry budget reservations directly —
        // only the root AUTHORIZED event holds amountReserved on the budget/allocation.
    }
}
