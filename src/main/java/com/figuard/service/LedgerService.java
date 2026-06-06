package com.figuard.service;

import com.figuard.api.dto.response.ChainDetailResponse;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.dto.response.SpendTreeNode;
import com.figuard.api.dto.response.SpendTreeResponse;
import com.figuard.api.mapper.BudgetMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.SpendEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AgentBudgetRepository budgetRepository;
    private final SpendEventRepository spendEventRepository;
    private final BudgetMapper budgetMapper;

    // -------------------------------------------------------------------------
    // Paginated ledger — all events for a budget, newest first
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<SpendEventResponse> getLedger(UUID budgetId, Tenant tenant,
                                               int page, int size,
                                               SpendDecision decision,
                                               String traceId) {
        AgentBudget budget = findBudgetForTenant(budgetId, tenant);

        PageRequest pageable = PageRequest.of(page, size);

        Page<SpendEvent> events;
        if (traceId != null && decision != null) {
            events = spendEventRepository
                .findByBudgetIdAndTraceIdAndDecisionOrderByCreatedAtDesc(budget.getId(), traceId, decision, pageable);
        } else if (traceId != null) {
            events = spendEventRepository
                .findByBudgetIdAndTraceIdOrderByCreatedAtDesc(budget.getId(), traceId, pageable);
        } else if (decision != null) {
            events = spendEventRepository
                .findByBudgetIdAndDecisionOrderByCreatedAtDesc(budget.getId(), decision, pageable);
        } else {
            events = spendEventRepository
                .findByBudgetIdOrderByCreatedAtDesc(budget.getId(), pageable);
        }

        return events.map(budgetMapper::toResponse);
    }

    // -------------------------------------------------------------------------
    // Spend tree — full causal chain rooted at this budget
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SpendTreeResponse getSpendTree(UUID budgetId, Tenant tenant) {
        AgentBudget budget = findBudgetForTenant(budgetId, tenant);

        // Fetch root events (no parent) — these are the entry points into the causal chain
        List<SpendEvent> roots = spendEventRepository
            .findByParentEventIdIsNullAndBudgetId(budget.getId());

        // Recursively build each root into a tree node
        List<SpendTreeNode> rootNodes = roots.stream()
            .map(this::buildNode)
            .toList();

        // Roll up totals across all nodes in the tree (recursive — roots-only was a bug)
        BigDecimal totalAuthorized = sumNodeDecision(rootNodes, SpendDecision.AUTHORIZED, SpendDecision.CONFIRMED);
        BigDecimal totalConfirmed  = sumNodeDecision(rootNodes, SpendDecision.CONFIRMED);
        int totalEvents            = countAllEvents(rootNodes);

        return SpendTreeResponse.builder()
            .budgetId(budget.getId())
            .totalAuthorized(totalAuthorized)
            .totalConfirmed(totalConfirmed)
            .totalEvents(totalEvents)
            .roots(rootNodes)
            .build();
    }

    // -------------------------------------------------------------------------
    // Recursive node builder
    // -------------------------------------------------------------------------

    private SpendTreeNode buildNode(SpendEvent event) {
        List<SpendEvent> children = spendEventRepository.findByParentEventId(event.getId());

        List<SpendTreeNode> childNodes = children.stream()
            .map(this::buildNode)
            .toList();

        return SpendTreeNode.builder()
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
            .denialMessage(event.getDenialMessage())
            .failureReason(event.getFailureReason())
            .parentEventId(event.getParentEvent() != null ? event.getParentEvent().getId() : null)
            .chainRootEventId(event.getChainRootEventId())
            .createdAt(event.getCreatedAt())
            .metadata(event.getMetadata())
            .children(childNodes.isEmpty() ? null : childNodes)
            .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentBudget findBudgetForTenant(UUID budgetId, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(budgetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found");
        }
        return budget;
    }

    private BigDecimal sumNodeDecision(List<SpendTreeNode> nodes, SpendDecision... decisions) {
        List<SpendDecision> target = List.of(decisions);
        BigDecimal sum = BigDecimal.ZERO;
        for (SpendTreeNode node : nodes) {
            if (target.contains(node.getDecision())) {
                BigDecimal amount = node.getDecision() == SpendDecision.CONFIRMED
                        && node.getConfirmedQuantity() != null
                    ? node.getConfirmedQuantity()
                    : node.getRequestedQuantity();
                sum = sum.add(amount);
            }
            if (node.getChildren() != null) {
                sum = sum.add(sumNodeDecision(node.getChildren(), decisions));
            }
        }
        return sum;
    }

    private int countAllEvents(List<SpendTreeNode> nodes) {
        int count = 0;
        for (SpendTreeNode node : nodes) {
            count++;
            if (node.getChildren() != null) {
                count += countAllEvents(node.getChildren());
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Chain detail — all events sharing a chainRootEventId
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChainDetailResponse getChainDetail(UUID chainRootEventId, Tenant tenant) {
        // Load the root event — validates existence and tenant ownership
        SpendEvent root = spendEventRepository.findById(chainRootEventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chain root event not found"));

        if (!root.getBudget().getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chain root event not found");
        }

        // All events in this chain (including root itself)
        List<SpendEvent> allEvents = spendEventRepository.findByChainRootEventId(chainRootEventId);

        // Build tree from root
        SpendTreeNode rootNode = buildNode(root);

        // Compute chain-level totals
        BigDecimal totalAuthorized = allEvents.stream()
            .filter(e -> e.getDecision() == SpendDecision.AUTHORIZED
                      || e.getDecision() == SpendDecision.CONFIRMED)
            .map(SpendEvent::getRequestedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalConfirmed = allEvents.stream()
            .filter(e -> e.getDecision() == SpendDecision.CONFIRMED)
            .map(e -> e.getConfirmedQuantity() != null ? e.getConfirmedQuantity() : e.getRequestedQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalChainSpend = allEvents.stream()
            .filter(e -> e.getDecision() == SpendDecision.AUTHORIZED
                      || e.getDecision() == SpendDecision.CONFIRMED)
            .map(SpendEvent::getRequestedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal chainCapRemaining = root.getMaxSubtreeQuantity() != null
            ? root.getMaxSubtreeQuantity().subtract(totalChainSpend).max(BigDecimal.ZERO)
            : null;

        java.time.OffsetDateTime lastActivityAt = allEvents.stream()
            .map(SpendEvent::getCreatedAt)
            .filter(t -> t != null)
            .max(java.util.Comparator.naturalOrder())
            .orElse(root.getCreatedAt());

        return ChainDetailResponse.builder()
            .chainRootEventId(chainRootEventId)
            .budgetId(root.getBudget().getId())
            .maxSubtreeQuantity(root.getMaxSubtreeQuantity())
            .totalChainSpend(totalChainSpend)
            .chainCapRemaining(chainCapRemaining)
            .currency(root.getCurrency() != null ? root.getCurrency().trim() : null)
            .totalEvents(allEvents.size())
            .totalAuthorized(totalAuthorized)
            .totalConfirmed(totalConfirmed)
            .chainStartedAt(root.getCreatedAt())
            .lastActivityAt(lastActivityAt)
            .roots(List.of(rootNode))
            .build();
    }
}
