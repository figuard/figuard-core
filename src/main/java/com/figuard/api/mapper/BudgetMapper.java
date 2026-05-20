package com.figuard.api.mapper;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.response.*;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
import com.figuard.security.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public class BudgetMapper {

    // Used at creation time — rawSessionToken is returned once and never again
    public BudgetResponse toResponse(AgentBudget budget, String rawSessionToken) {
        return buildBudgetResponse(budget, rawSessionToken);
    }

    // Used for reads — sessionToken is always null
    public BudgetResponse toResponse(AgentBudget budget) {
        return buildBudgetResponse(budget, null);
    }

    private BudgetResponse buildBudgetResponse(AgentBudget budget, String rawSessionToken) {
        List<AllocationResponse> allocations = Collections.emptyList();
        if (budget.getAllocations() != null) {
            allocations = budget.getAllocations().stream()
                .map(this::toResponse)
                .toList();
        }

        List<BudgetTokenResponse> tokens = null;
        if (rawSessionToken != null) {
            tokens = List.of(BudgetTokenResponse.builder()
                .category("default")
                .sessionToken(rawSessionToken)
                .sessionTokenPrefix(budget.getSessionTokenPrefix())
                .unit(budget.getUnit())
                .currency(budget.getCurrency() != null ? budget.getCurrency().trim() : null)
                .build());
        }

        return BudgetResponse.builder()
            .id(budget.getId())
            .userId(budget.getUserId())
            .externalReference(budget.getExternalReference())
            .intentContext(budget.getIntentContext())
            .intentTags(budget.getIntentTags() != null
                ? Arrays.asList(budget.getIntentTags()) : null)
            .tokens(tokens)
            .totalLimit(budget.getTotalLimit())
            .maxTransactionQuantity(budget.getMaxTransactionQuantity())
            .currency(budget.getCurrency() != null ? budget.getCurrency().trim() : null)
            .unit(budget.getUnit())
            .quantitySpent(budget.getQuantitySpent())
            .quantityReserved(budget.getQuantityReserved())
            .availableQuantity(budget.availableQuantity())
            .softLimit(budget.getSoftLimit())
            .authorizationExpirySeconds(budget.getAuthorizationExpirySeconds())
            .velocityMaxPerMinute(budget.getVelocityMaxPerMinute())
            .velocityMaxAmountPerHour(budget.getVelocityMaxAmountPerHour())
            .velocityMaxPerDay(budget.getVelocityMaxPerDay())
            .anomalyDetectionEnabled(budget.isAnomalyDetectionEnabled())
            .autoPauseOnAnomaly(budget.isAutoPauseOnAnomaly())
            .status(budget.getStatus())
            .allocations(allocations)
            .expiresAt(budget.getExpiresAt())
            .cancelledAt(budget.getCancelledAt())
            .createdAt(budget.getCreatedAt())
            .metadata(budget.getMetadata())
            .traceId(MDC.get(TraceIdFilter.TRACE_ID_KEY))
            .build();
    }

    public AllocationResponse toResponse(BudgetAllocation allocation) {
        return AllocationResponse.builder()
            .id(allocation.getId())
            .category(allocation.getCategory())
            .allowedCategories(Arrays.asList(allocation.getAllowedCategories()))
            .forbiddenItemTypes(allocation.getForbiddenItemTypes() != null
                ? Arrays.asList(allocation.getForbiddenItemTypes()) : null)
            .enforcementMode(allocation.getEnforcementMode())
            .limit(allocation.getTotalLimit())
            .quantitySpent(allocation.getQuantitySpent())
            .quantityReserved(allocation.getQuantityReserved())
            .availableQuantity(allocation.availableQuantity())
            .status(allocation.getStatus())
            .build();
    }

    public SpendEventResponse toResponse(SpendEvent event) {
        return SpendEventResponse.builder()
            .id(event.getId())
            .decision(event.getDecision())
            .agentId(event.getAgentId())
            .agentType(event.getAgentType())
            .actionType(event.getActionType())
            .description(event.getDescription())
            .requestedQuantity(event.getRequestedQuantity())
            .confirmedQuantity(event.getConfirmedQuantity())
            .currency(event.getCurrency() != null ? event.getCurrency().trim() : null)
            .entityId(event.getEntityId())
            .claimedCategory(event.getClaimedCategory())
            .claimedItemType(event.getClaimedItemType())
            .intentContext(event.getIntentContext())
            .idempotencyKey(event.getIdempotencyKey())
            .denialReason(event.getDenialReason() != null
                ? DenialCode.valueOf(event.getDenialReason()) : null)
            .failureReason(event.getFailureReason())
            .parentEventId(event.getParentEvent() != null
                ? event.getParentEvent().getId() : null)
            .traceId(event.getTraceId())
            .createdAt(event.getCreatedAt())
            .metadata(event.getMetadata())
            .build();
    }

    public BudgetSnapshot toBudgetSnapshot(AgentBudget budget) {
        return BudgetSnapshot.builder()
            .id(budget.getId())
            .totalLimit(budget.getTotalLimit())
            .quantitySpent(budget.getQuantitySpent())
            .quantityReserved(budget.getQuantityReserved())
            .availableQuantity(budget.availableQuantity())
            .status(budget.getStatus())
            .build();
    }

    public AllocationSnapshot toAllocationSnapshot(BudgetAllocation allocation) {
        return AllocationSnapshot.builder()
            .category(allocation.getCategory())
            .limit(allocation.getTotalLimit())
            .quantitySpent(allocation.getQuantitySpent())
            .quantityReserved(allocation.getQuantityReserved())
            .availableQuantity(allocation.availableQuantity())
            .status(allocation.getStatus())
            .build();
    }

    public AgentBudget toEntity(CreateBudgetRequest request, Tenant tenant) {
        AgentBudget budget = new AgentBudget();
        budget.setTenant(tenant);
        budget.setUserId(request.getUserId());
        budget.setExternalReference(request.getExternalReference());
        budget.setIntentContext(request.getIntentContext() != null ? request.getIntentContext() : "");
        budget.setIntentTags(request.getIntentTags() != null
            ? request.getIntentTags().toArray(new String[0]) : null);
        budget.setTotalLimit(request.getTotalLimit());
        budget.setMaxTransactionQuantity(request.getMaxTransactionQuantity());
        budget.setCurrency(request.getCurrency());
        budget.setUnit(request.getUnit());
        budget.setSoftLimit(request.getSoftLimit());
        budget.setEntityDedupEnabled(request.isEntityDedupEnabled());
        budget.setAnomalyDetectionEnabled(request.isAnomalyDetectionEnabled());
        budget.setAutoPauseOnAnomaly(request.isAutoPauseOnAnomaly());
        if (request.getAnomalyPauseThresholdMultiplier() != null) {
            budget.setAnomalyPauseThresholdMultiplier(request.getAnomalyPauseThresholdMultiplier());
        }
        if (request.getAnomalyMinSampleSize() != null) {
            budget.setAnomalyMinSampleSize(request.getAnomalyMinSampleSize());
        }
        budget.setAuthorizationExpirySeconds(request.getAuthorizationExpirySeconds());
        budget.setVelocityMaxPerMinute(request.getVelocityMaxPerMinute());
        budget.setVelocityMaxAmountPerHour(request.getVelocityMaxAmountPerHour());
        budget.setVelocityMaxPerDay(request.getVelocityMaxPerDay());
        budget.setExpiresAt(request.getExpiresAt());
        budget.setMetadata(request.getMetadata());
        if (request.getEntitlementItemId() != null) {
            budget.setEntitlementItemId(request.getEntitlementItemId());
        }
        return budget;
    }

    public BudgetAllocation toEntity(AllocationRequest request, AgentBudget budget, Tenant tenant) {
        BudgetAllocation allocation = new BudgetAllocation();
        allocation.setParentBudget(budget);
        allocation.setTenant(tenant);
        allocation.setCategory(request.getCategory());
        allocation.setAllowedCategories(request.getAllowedCategories().toArray(new String[0]));
        allocation.setForbiddenItemTypes(request.getForbiddenItemTypes() != null
            ? request.getForbiddenItemTypes().toArray(new String[0]) : null);
        allocation.setEnforcementMode(request.getEnforcementMode() != null
            ? request.getEnforcementMode() : com.figuard.domain.enums.EnforcementMode.CATEGORY_CONSTRAINED);
        allocation.setTotalLimit(request.getLimit());
        allocation.setCurrency(budget.getCurrency());
        return allocation;
    }
}
