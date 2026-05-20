package com.figuard.service;

import com.figuard.api.dto.request.CreateEntitlementItemRequest;
import com.figuard.api.dto.request.CreateSubscriptionRequest;
import com.figuard.api.dto.response.EntitlementItemResponse;
import com.figuard.api.dto.response.SubscriptionResponse;
import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.Subscription;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SubscriptionStatus;
import com.figuard.domain.repository.EntitlementItemRepository;
import com.figuard.domain.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final EntitlementItemRepository entitlementItemRepository;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Transactional
    public SubscriptionResponse createSubscription(CreateSubscriptionRequest request, Tenant tenant) {
        // Idempotent: one subscription per externalSubscriberId per tenant
        subscriptionRepository.findByTenantIdAndExternalSubscriberId(
                tenant.getId(), request.getExternalSubscriberId())
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A subscription already exists for externalSubscriberId '"
                        + request.getExternalSubscriberId() + "'. "
                        + "Use GET /subscriptions?subscriberId=... to retrieve it.");
                });

        Subscription sub = new Subscription();
        sub.setTenant(tenant);
        sub.setExternalSubscriberId(request.getExternalSubscriberId());
        sub.setName(request.getName());
        sub.setDescription(request.getDescription());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setSubscriptionStartDate(OffsetDateTime.now());
        sub.setMetadata(request.getMetadata());

        // Persist subscription first so entitlement items can reference it
        sub = subscriptionRepository.save(sub);

        for (CreateEntitlementItemRequest itemReq : request.getEntitlementItems()) {
            EntitlementItem item = buildEntitlementItem(itemReq, sub);
            entitlementItemRepository.save(item);
            sub.getEntitlementItems().add(item);
        }

        log.info("Subscription created: id={} tenant={} subscriber={}",
                sub.getId(), tenant.getId(), request.getExternalSubscriberId());

        return toResponse(sub);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(UUID subscriptionId, Tenant tenant) {
        return toResponse(findAndVerify(subscriptionId, tenant));
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscriptionBySubscriberId(String externalSubscriberId, Tenant tenant) {
        Subscription sub = subscriptionRepository
                .findByTenantIdAndExternalSubscriberId(tenant.getId(), externalSubscriberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No subscription found for subscriberId '" + externalSubscriberId + "'"));
        return toResponse(sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> listSubscriptions(Tenant tenant) {
        return subscriptionRepository.findByTenantId(tenant.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Transactional
    public SubscriptionResponse pauseSubscription(UUID subscriptionId, Tenant tenant) {
        Subscription sub = findAndVerifyWithLock(subscriptionId, tenant);
        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Subscription is not ACTIVE — current status: " + sub.getStatus());
        }
        sub.setStatus(SubscriptionStatus.PAUSED);
        subscriptionRepository.save(sub);
        log.info("Subscription paused: id={} tenant={}", subscriptionId, tenant.getId());
        return toResponse(sub);
    }

    @Transactional
    public SubscriptionResponse resumeSubscription(UUID subscriptionId, Tenant tenant) {
        Subscription sub = findAndVerifyWithLock(subscriptionId, tenant);
        if (sub.getStatus() != SubscriptionStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Subscription is not PAUSED — current status: " + sub.getStatus());
        }
        sub.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);
        log.info("Subscription resumed: id={} tenant={}", subscriptionId, tenant.getId());
        return toResponse(sub);
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(UUID subscriptionId, Tenant tenant) {
        Subscription sub = findAndVerifyWithLock(subscriptionId, tenant);
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription is already CANCELLED");
        }
        sub.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(sub);
        log.info("Subscription cancelled: id={} tenant={}", subscriptionId, tenant.getId());
        return toResponse(sub);
    }

    // -------------------------------------------------------------------------
    // Entitlement item management
    // -------------------------------------------------------------------------

    @Transactional
    public EntitlementItemResponse addEntitlementItem(
            UUID subscriptionId, CreateEntitlementItemRequest request, Tenant tenant) {
        Subscription sub = findAndVerify(subscriptionId, tenant);
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot add entitlement items to a CANCELLED subscription");
        }
        EntitlementItem item = buildEntitlementItem(request, sub);
        item = entitlementItemRepository.save(item);
        log.info("EntitlementItem added: id={} subscriptionId={} name={}",
                item.getId(), subscriptionId, item.getName());
        return toItemResponse(item);
    }

    @Transactional(readOnly = true)
    public List<EntitlementItemResponse> listEntitlementItems(UUID subscriptionId, Tenant tenant) {
        findAndVerify(subscriptionId, tenant); // access check
        return entitlementItemRepository.findBySubscriptionId(subscriptionId)
                .stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EntitlementItemResponse getEntitlementItem(
            UUID subscriptionId, UUID itemId, Tenant tenant) {
        findAndVerify(subscriptionId, tenant); // access check
        EntitlementItem item = entitlementItemRepository.findById(itemId)
                .filter(i -> i.getSubscription().getId().equals(subscriptionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Entitlement item not found"));
        return toItemResponse(item);
    }

    /**
     * Manual reset — sets currentPeriodConsumed back to zero and state to NORMAL.
     * Used when auto-renewal is temporarily bypassed or for testing.
     */
    @Transactional
    public EntitlementItemResponse resetEntitlementItem(
            UUID subscriptionId, UUID itemId, Tenant tenant) {
        findAndVerify(subscriptionId, tenant);
        EntitlementItem item = entitlementItemRepository.findByIdWithLock(itemId)
                .filter(i -> i.getSubscription().getId().equals(subscriptionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Entitlement item not found"));

        item.setCurrentPeriodConsumed(java.math.BigDecimal.ZERO);
        item.setState(com.figuard.domain.enums.EntitlementState.NORMAL);
        item.setLastStateTransitionAt(null);
        entitlementItemRepository.save(item);
        log.info("EntitlementItem manually reset: id={} subscriptionId={}", itemId, subscriptionId);
        return toItemResponse(item);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Subscription findAndVerify(UUID subscriptionId, Tenant tenant) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Subscription not found"));
        if (!sub.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found");
        }
        return sub;
    }

    private Subscription findAndVerifyWithLock(UUID subscriptionId, Tenant tenant) {
        Subscription sub = subscriptionRepository.findByIdWithLock(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Subscription not found"));
        if (!sub.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found");
        }
        return sub;
    }

    private EntitlementItem buildEntitlementItem(CreateEntitlementItemRequest req, Subscription sub) {
        EntitlementItem item = new EntitlementItem();
        item.setSubscription(sub);
        item.setName(req.getName());
        item.setLimitUnit(req.getLimitUnit());
        item.setLimitQuantity(req.getLimitQuantity());
        item.setRenewalPeriod(req.getRenewalPeriod());
        item.setOveragePolicy(req.getOveragePolicy() != null
                ? req.getOveragePolicy()
                : com.figuard.domain.enums.OveragePolicy.BLOCK);
        item.setWarnAtPercentage(req.getWarnAtPercentage() != null
                ? req.getWarnAtPercentage()
                : 80);
        item.setNextRenewalAt(computeFirstRenewal(req));
        return item;
    }

    private OffsetDateTime computeFirstRenewal(CreateEntitlementItemRequest req) {
        OffsetDateTime now = OffsetDateTime.now();
        int anchorDay = req.getRenewalAnchorDay() != null ? req.getRenewalAnchorDay() : now.getDayOfMonth();
        return switch (req.getRenewalPeriod()) {
            case MONTHLY   -> now.plusMonths(1).withDayOfMonth(Math.min(anchorDay, 28));
            case QUARTERLY -> now.plusMonths(3).withDayOfMonth(Math.min(anchorDay, 28));
            case ANNUALLY  -> now.plusYears(1).withDayOfMonth(Math.min(anchorDay, 28));
        };
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private SubscriptionResponse toResponse(Subscription sub) {
        return SubscriptionResponse.builder()
                .id(sub.getId())
                .externalSubscriberId(sub.getExternalSubscriberId())
                .name(sub.getName())
                .description(sub.getDescription())
                .status(sub.getStatus())
                .subscriptionStartDate(sub.getSubscriptionStartDate())
                .entitlementItems(sub.getEntitlementItems().stream()
                        .map(this::toItemResponse)
                        .toList())
                .metadata(sub.getMetadata())
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }

    public EntitlementItemResponse toItemResponse(EntitlementItem item) {
        return EntitlementItemResponse.builder()
                .id(item.getId())
                .subscriptionId(item.getSubscription().getId())
                .name(item.getName())
                .limitUnit(item.getLimitUnit())
                .limitQuantity(item.getLimitQuantity())
                .currentPeriodConsumed(item.getCurrentPeriodConsumed())
                .remaining(item.remaining())
                .consumedPercentage(item.consumedPercentage())
                .warnAtPercentage(item.getWarnAtPercentage())
                .renewalPeriod(item.getRenewalPeriod())
                .nextRenewalAt(item.getNextRenewalAt())
                .overagePolicy(item.getOveragePolicy())
                .state(item.getState())
                .lastStateTransitionAt(item.getLastStateTransitionAt())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
