package com.figuard.api.mapper;

import com.figuard.api.dto.request.AllocationRequest;
import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.response.*;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
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

        return BudgetResponse.builder()
            .id(budget.getId())
            .userId(budget.getUserId())
            .externalReference(budget.getExternalReference())
            .intentContext(budget.getIntentContext())
            .intentTags(budget.getIntentTags() != null
                ? Arrays.asList(budget.getIntentTags()) : null)
            .sessionToken(rawSessionToken)
            .sessionTokenPrefix(budget.getSessionTokenPrefix())
            .totalLimit(budget.getTotalLimit())
            .maxTransactionAmount(budget.getMaxTransactionAmount())
            .currency(budget.getCurrency() != null ? budget.getCurrency().trim() : null)
            .amountSpent(budget.getAmountSpent())
            .amountReserved(budget.getAmountReserved())
            .availableAmount(budget.availableAmount())
            .softLimit(budget.getSoftLimit())
            .status(budget.getStatus())
            .allocations(allocations)
            .expiresAt(budget.getExpiresAt())
            .cancelledAt(budget.getCancelledAt())
            .createdAt(budget.getCreatedAt())
            .metadata(budget.getMetadata())
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
            .amountSpent(allocation.getAmountSpent())
            .amountReserved(allocation.getAmountReserved())
            .availableAmount(allocation.availableAmount())
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
            .requestedAmount(event.getRequestedAmount())
            .confirmedAmount(event.getConfirmedAmount())
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
            .createdAt(event.getCreatedAt())
            .metadata(event.getMetadata())
            .build();
    }

    public BudgetSnapshot toBudgetSnapshot(AgentBudget budget) {
        return BudgetSnapshot.builder()
            .id(budget.getId())
            .totalLimit(budget.getTotalLimit())
            .amountSpent(budget.getAmountSpent())
            .amountReserved(budget.getAmountReserved())
            .availableAmount(budget.availableAmount())
            .status(budget.getStatus())
            .build();
    }

    public AllocationSnapshot toAllocationSnapshot(BudgetAllocation allocation) {
        return AllocationSnapshot.builder()
            .category(allocation.getCategory())
            .limit(allocation.getTotalLimit())
            .amountSpent(allocation.getAmountSpent())
            .amountReserved(allocation.getAmountReserved())
            .availableAmount(allocation.availableAmount())
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
        budget.setMaxTransactionAmount(request.getMaxTransactionAmount());
        budget.setCurrency(request.getCurrency() != null ? request.getCurrency() : "USD");
        budget.setSoftLimit(request.getSoftLimit());
        budget.setEntityDedupEnabled(request.isEntityDedupEnabled());
        budget.setAnomalyDetectionEnabled(request.isAnomalyDetectionEnabled());
        if (request.getAnomalyPauseThresholdMultiplier() != null) {
            budget.setAnomalyPauseThresholdMultiplier(request.getAnomalyPauseThresholdMultiplier());
        }
        if (request.getAnomalyMinSampleSize() != null) {
            budget.setAnomalyMinSampleSize(request.getAnomalyMinSampleSize());
        }
        budget.setExpiresAt(request.getExpiresAt());
        budget.setMetadata(request.getMetadata());
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
