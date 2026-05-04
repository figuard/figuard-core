package com.figuard.service;

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
                                               SpendDecision decision) {
        AgentBudget budget = findBudgetForTenant(budgetId, tenant);

        PageRequest pageable = PageRequest.of(page, size);

        Page<SpendEvent> events = decision != null
            ? spendEventRepository.findByBudgetIdAndDecisionOrderByCreatedAtDesc(budget.getId(), decision, pageable)
            : spendEventRepository.findByBudgetIdOrderByCreatedAtDesc(budget.getId(), pageable);

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

        // Roll up totals across all nodes in the tree
        BigDecimal totalAuthorized = sumDecision(roots, SpendDecision.AUTHORIZED, SpendDecision.CONFIRMED);
        BigDecimal totalConfirmed  = sumDecision(roots, SpendDecision.CONFIRMED);
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
            .parentEventId(event.getParentEvent() != null ? event.getParentEvent().getId() : null)
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

    private BigDecimal sumDecision(List<SpendEvent> events, SpendDecision... decisions) {
        List<SpendDecision> target = List.of(decisions);
        return events.stream()
            .filter(e -> target.contains(e.getDecision()))
            .map(SpendEvent::getRequestedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
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
}
