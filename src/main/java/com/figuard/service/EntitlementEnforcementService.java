package com.figuard.service;

import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.OveragePolicy;
import com.figuard.domain.repository.EntitlementItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Enforces entitlement item limits during the authorization lifecycle.
 *
 * Called from AuthorizationService (check + consume on authorize),
 * PaymentLifecycleService (adjust on confirm, release on fail/void).
 *
 * Lock ordering:
 *   budget (pessimistic, Step 1 of AuthorizationService)
 *     → entitlement item (pessimistic, acquired here via findByIdWithLock)
 *       → delegate cap → fleet allocation
 *
 * All methods that mutate state require an active transaction
 * (Propagation.MANDATORY) — they must be called within the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementEnforcementService {

    private final EntitlementItemRepository entitlementItemRepository;
    private final EntitlementItemService entitlementItemService;

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public sealed interface CheckResult permits CheckResult.Approved,
                                                 CheckResult.Denied,
                                                 CheckResult.WarnOnly {

        record Approved(EntitlementItem item) implements CheckResult {}

        record Denied(DenialCode code, String message) implements CheckResult {
            public boolean isDenied() { return true; }
        }

        record WarnOnly(EntitlementItem item) implements CheckResult {
            /** WARN_ONLY: limit reached but policy allows spend to continue. */
        }

        default boolean isDenied() { return false; }
    }

    // -------------------------------------------------------------------------
    // Check (called during AuthorizationService — item already locked by caller)
    // -------------------------------------------------------------------------

    /**
     * Checks whether the entitlement item can accommodate the requested quantity.
     * Does NOT mutate state — consume() must be called separately in approve().
     *
     * @param item     the already-locked entitlement item (lock acquired in AuthorizationService Step 1)
     * @param requested the quantity being requested
     * @return CheckResult — Approved, Denied, or WarnOnly
     */
    public CheckResult check(EntitlementItem item, BigDecimal requested) {
        // Subscription-level gate: paused/cancelled subscriptions caught upstream
        // by AuthorizationService checking budget.subscriptionId status.
        // Here we only enforce the item-level balance.

        if (item.getState() == EntitlementState.LIMIT_REACHED) {
            // State is already LIMIT_REACHED from a previous request.
            // Re-check actual balance in case it was manually reset.
            if (!item.canAccommodate(requested)) {
                return handleLimitReached(item, requested);
            }
            // Balance was reset manually — state will self-correct on next evaluateStateTransition call
        }

        if (!item.canAccommodate(requested)) {
            return handleLimitReached(item, requested);
        }

        return new CheckResult.Approved(item);
    }

    private CheckResult handleLimitReached(EntitlementItem item, BigDecimal requested) {
        return switch (item.getOveragePolicy()) {
            case BLOCK -> new CheckResult.Denied(
                DenialCode.ENTITLEMENT_LIMIT_REACHED,
                "Entitlement '" + item.getName() + "' has " + item.remaining()
                    + " " + item.getLimitUnit() + " remaining, requested " + requested
                    + ". Limit: " + item.getLimitQuantity() + " per period.");
            case WARN_ONLY -> {
                log.info("EntitlementItem WARN_ONLY limit reached: id={} subscriptionId={} remaining={} requested={}",
                        item.getId(), item.getSubscription().getId(), item.remaining(), requested);
                yield new CheckResult.WarnOnly(item);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Consume (called in AuthorizationService.approve())
    // -------------------------------------------------------------------------

    /**
     * Reserves quantity against the entitlement item after authorization is approved.
     * Evaluates state transitions (NORMAL → APPROACHING → LIMIT_REACHED) and fires
     * webhooks if a transition occurred.
     *
     * Must be called within the same transaction as authorize().
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EntitlementItem item, BigDecimal quantity, SpendEvent event) {
        item.setCurrentPeriodConsumed(item.getCurrentPeriodConsumed().add(quantity));

        // Link spend event to this entitlement item for ledger queries
        event.setEntitlementItemId(item.getId());

        entitlementItemRepository.save(item);

        log.info("EntitlementItem consumed: id={} subscriptionId={} quantity={} totalConsumed={} remaining={}",
                item.getId(), item.getSubscription().getId(),
                quantity, item.getCurrentPeriodConsumed(), item.remaining());

        // Evaluate whether this consumption triggers a state transition
        entitlementItemService.evaluateStateTransition(item);
    }

    // -------------------------------------------------------------------------
    // Release (called on fail / void in PaymentLifecycleService)
    // -------------------------------------------------------------------------

    /**
     * Releases reserved quantity back to the entitlement item.
     * Called when a spend event transitions to FAILED or VOIDED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID entitlementItemId, BigDecimal quantity) {
        EntitlementItem item = entitlementItemRepository.findByIdWithLock(entitlementItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EntitlementItem disappeared during release: " + entitlementItemId));

        BigDecimal newConsumed = item.getCurrentPeriodConsumed().subtract(quantity).max(BigDecimal.ZERO);
        item.setCurrentPeriodConsumed(newConsumed);
        entitlementItemRepository.save(item);

        log.info("EntitlementItem released: id={} quantity={} newConsumed={}",
                entitlementItemId, quantity, newConsumed);

        // Re-evaluate state — may transition back from LIMIT_REACHED → APPROACHING
        entitlementItemService.evaluateStateTransition(item);
    }

    // -------------------------------------------------------------------------
    // Adjust (called on confirm in PaymentLifecycleService)
    // -------------------------------------------------------------------------

    /**
     * Adjusts consumed quantity when the confirmed amount differs from the reserved amount.
     * Called when confirmedQuantity < requestedQuantity (partial confirmation).
     * The difference is released back to the entitlement pool.
     *
     * No-op when confirmedQuantity == requestedQuantity (exact confirmation).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adjust(UUID entitlementItemId, BigDecimal requestedQuantity, BigDecimal confirmedQuantity) {
        BigDecimal delta = requestedQuantity.subtract(confirmedQuantity);
        if (delta.compareTo(BigDecimal.ZERO) <= 0) return; // no adjustment needed

        EntitlementItem item = entitlementItemRepository.findByIdWithLock(entitlementItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EntitlementItem disappeared during adjustment: " + entitlementItemId));

        BigDecimal newConsumed = item.getCurrentPeriodConsumed().subtract(delta).max(BigDecimal.ZERO);
        item.setCurrentPeriodConsumed(newConsumed);
        entitlementItemRepository.save(item);

        log.info("EntitlementItem adjusted (partial confirm): id={} reserved={} confirmed={} released={}",
                entitlementItemId, requestedQuantity, confirmedQuantity, delta);

        entitlementItemService.evaluateStateTransition(item);
    }

    // -------------------------------------------------------------------------
    // Load with lock (called from AuthorizationService Step 1)
    // -------------------------------------------------------------------------

    /**
     * Loads the entitlement item with a pessimistic write lock.
     * Must be called at Step 1 of authorization (alongside budget lock) to maintain
     * consistent lock ordering across concurrent transactions.
     */
    public EntitlementItem loadWithLock(UUID entitlementItemId) {
        return entitlementItemRepository.findByIdWithLock(entitlementItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        DenialCode.ENTITLEMENT_NOT_FOUND.name()
                        + ": entitlementItemId " + entitlementItemId + " not found"));
    }
}
