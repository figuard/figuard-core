package com.figuard.service;

import com.figuard.api.dto.request.CounterfactualReplayRequest;
import com.figuard.api.dto.request.HypotheticalAllocation;
import com.figuard.api.dto.request.HypotheticalPolicy;
import com.figuard.api.dto.response.*;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAllocation;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAllocationRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.exception.BudgetNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReplayService {

    private final SpendEventRepository eventRepository;
    private final AgentBudgetRepository budgetRepository;
    private final BudgetAllocationRepository allocationRepository;

    // -------------------------------------------------------------------------
    // Full replay
    // -------------------------------------------------------------------------

    public BudgetReplayResponse replay(
        UUID budgetId,
        OffsetDateTime from,
        OffsetDateTime until,
        boolean includeDenied,
        boolean includeStateSnapshots,
        int pageSize,
        String pageToken,
        Tenant tenant
    ) {
        AgentBudget budget = requireBudget(budgetId, tenant);
        List<BudgetAllocation> allocations = allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(budgetId);

        OffsetDateTime effectiveFrom  = from  != null ? from  : OffsetDateTime.parse("2000-01-01T00:00:00Z");
        OffsetDateTime effectiveUntil = until != null ? until : OffsetDateTime.now();

        List<SpendEvent> events = eventRepository.findByBudgetIdInWindowOrderByCreatedAtAsc(
            budgetId, effectiveFrom, effectiveUntil);

        if (!includeDenied) {
            events = events.stream()
                .filter(e -> e.getDecision() != SpendDecision.DENIED)
                .toList();
        }

        // Cursor-based pagination on the in-memory list (replay windows are typically <1000 events)
        int offset = decodePageToken(pageToken);
        List<SpendEvent> page = events.stream().skip(offset).limit(pageSize).toList();
        String nextToken = (offset + pageSize < events.size())
            ? encodePageToken(offset + pageSize) : null;

        // Build initial state — snapshotAt uses budget.createdAt regardless of window bounds
        OffsetDateTime windowOrigin = events.isEmpty() ? budget.getCreatedAt() : events.get(0).getCreatedAt();
        ReplayBudgetState initialState = buildInitialState(budget, allocations, windowOrigin);

        // Project forward through the full event list to get final state,
        // but only build detailed frames for the current page.
        List<ReplayFrame> frames = new ArrayList<>();
        ReplayBudgetState currentState = initialState;
        OffsetDateTime previousTime = windowOrigin;

        for (int i = 0; i < events.size(); i++) {
            SpendEvent event = events.get(i);
            long millis = Duration.between(previousTime, event.getCreatedAt()).toMillis();
            ReplayEventDetail detail = toEventDetail(event, millis);

            ReplayBudgetState stateAfter = includeStateSnapshots
                ? projectStateAfterEvent(currentState, event, allocations)
                : null;

            if (i >= offset && i < offset + pageSize) {
                frames.add(ReplayFrame.builder()
                    .eventIndex(i)
                    .event(detail)
                    .stateAfter(stateAfter)
                    .build());
            }

            if (stateAfter != null) currentState = stateAfter;
            else if (includeStateSnapshots) currentState = projectStateAfterEvent(currentState, event, allocations);
            else currentState = projectStateAfterEvent(currentState, event, allocations);

            previousTime = event.getCreatedAt();
        }

        ReplaySummary summary = buildSummary(events);
        long durationSeconds = Duration.between(effectiveFrom, effectiveUntil).getSeconds();

        return BudgetReplayResponse.builder()
            .budgetId(budgetId)
            .replayWindow(BudgetReplayResponse.ReplayWindow.builder()
                .from(effectiveFrom)
                .until(effectiveUntil)
                .durationSeconds(durationSeconds)
                .build())
            .summary(summary)
            .initialState(initialState)
            .events(frames)
            .finalState(currentState)
            .nextPageToken(nextToken)
            .build();
    }

    // -------------------------------------------------------------------------
    // Point-in-time state
    // -------------------------------------------------------------------------

    public PointInTimeStateResponse getStateAt(UUID budgetId, OffsetDateTime at, Tenant tenant) {
        AgentBudget budget = requireBudget(budgetId, tenant);
        List<BudgetAllocation> allocations = allocationRepository.findByParentBudgetIdOrderByCreatedAtAsc(budgetId);

        List<SpendEvent> events = eventRepository.findByBudgetIdUpToOrderByCreatedAtAsc(budgetId, at);

        ReplayBudgetState state = buildInitialState(budget, allocations, budget.getCreatedAt());
        for (SpendEvent event : events) {
            state = projectStateAfterEvent(state, event, allocations);
        }

        return PointInTimeStateResponse.builder()
            .budgetId(budgetId)
            .projectedAt(at)
            .eventsApplied(events.size())
            .state(state)
            .build();
    }

    // -------------------------------------------------------------------------
    // Timeline (lightweight — no state snapshots)
    // -------------------------------------------------------------------------

    public TimelineResponse getTimeline(UUID budgetId, OffsetDateTime from, OffsetDateTime until, Tenant tenant) {
        AgentBudget budget = requireBudget(budgetId, tenant);

        OffsetDateTime effectiveFrom  = from  != null ? from  : OffsetDateTime.parse("2000-01-01T00:00:00Z");
        OffsetDateTime effectiveUntil = until != null ? until : OffsetDateTime.now();

        List<SpendEvent> events = eventRepository.findByBudgetIdInWindowOrderByCreatedAtAsc(
            budgetId, effectiveFrom, effectiveUntil);

        List<TimelineEventItem> items = new ArrayList<>();
        OffsetDateTime previousTime = effectiveFrom;

        for (int i = 0; i < events.size(); i++) {
            SpendEvent e = events.get(i);
            long millis = Duration.between(previousTime, e.getCreatedAt()).toMillis();
            items.add(TimelineEventItem.builder()
                .eventIndex(i)
                .eventId(e.getId())
                .agentId(e.getAgentId())
                .decision(e.getDecision().name())
                .requestedQuantity(e.getRequestedQuantity())
                .claimedCategory(e.getClaimedCategory())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt())
                .millisSincePrevious(millis)
                .build());
            previousTime = e.getCreatedAt();
        }

        return TimelineResponse.builder()
            .budgetId(budgetId)
            .totalEvents(events.size())
            .timeline(items)
            .build();
    }

    // -------------------------------------------------------------------------
    // Counterfactual replay
    // -------------------------------------------------------------------------

    public CounterfactualReplayResponse replayCounterfactual(
        UUID budgetId,
        CounterfactualReplayRequest request,
        Tenant tenant
    ) {
        requireBudget(budgetId, tenant);

        OffsetDateTime effectiveFrom  = request.getFrom()  != null ? request.getFrom()  : OffsetDateTime.now().minusDays(30);
        OffsetDateTime effectiveUntil = request.getUntil() != null ? request.getUntil() : OffsetDateTime.now();

        // Replay against events that consumed (or would have consumed) budget:
        // CONFIRMED = completed spend, AUTHORIZED = in-flight reservations.
        // DENIED events were already rejected; FAILED/VOIDED never consumed budget.
        List<SpendEvent> spendEvents = eventRepository
            .findByBudgetIdInWindowOrderByCreatedAtAsc(budgetId, effectiveFrom, effectiveUntil)
            .stream()
            .filter(e -> e.getDecision() == SpendDecision.AUTHORIZED
                      || e.getDecision() == SpendDecision.CONFIRMED)
            .toList();

        // Determine policy source
        boolean usingManifest = request.getManifestVersion() != null && !request.getManifestVersion().isBlank();
        HypotheticalPolicy policy = request.getHypotheticalPolicy();

        // manifestVersion is wired but not yet resolved (declared manifests land V1-post Priority 2)
        // For now, a manifest_version request falls through with no additional constraints.

        List<CounterfactualDelta> deltas = new ArrayList<>();
        HypotheticalPolicyEvaluator evaluator = new HypotheticalPolicyEvaluator(policy);

        int actualCount = spendEvents.size();
        int hypotheticalDenied = 0;
        BigDecimal totalSpent = BigDecimal.ZERO;

        for (SpendEvent event : spendEvents) {
            String hypotheticalDecision = evaluator.evaluate(event);
            if ("AUTHORIZED".equals(hypotheticalDecision)) {
                evaluator.advance(event);
                BigDecimal qty = event.getConfirmedQuantity() != null
                    ? event.getConfirmedQuantity() : event.getRequestedQuantity();
                totalSpent = totalSpent.add(qty);
            } else {
                hypotheticalDenied++;
                deltas.add(CounterfactualDelta.builder()
                    .eventId(event.getId())
                    .actualDecision(event.getDecision().name())
                    .hypotheticalDecision(hypotheticalDecision)
                    .hypotheticalDenialReason(evaluator.getLastDenialReason())
                    .requestedQuantity(event.getRequestedQuantity())
                    .agentId(event.getAgentId())
                    .description(event.getDescription())
                    .claimedCategory(event.getClaimedCategory())
                    .build());
            }
        }

        return CounterfactualReplayResponse.builder()
            .budgetId(budgetId)
            .policySource(CounterfactualReplayResponse.PolicySource.builder()
                .type(usingManifest ? "manifest_version" : "inline")
                .manifestVersion(usingManifest ? request.getManifestVersion() : null)
                .build())
            .actualPolicySummary(CounterfactualReplayResponse.PolicySummary.builder()
                .authorizedCount(actualCount)
                .deniedCount(0)
                .totalQuantitySpent(totalSpent)
                .build())
            .hypotheticalPolicySummary(CounterfactualReplayResponse.PolicySummary.builder()
                .authorizedCount(actualCount - hypotheticalDenied)
                .deniedCount(hypotheticalDenied)
                .totalQuantitySpent(totalSpent)
                .additionalDenials(hypotheticalDenied)
                .build())
            .deltaEvents(deltas)
            .build();
    }

    // -------------------------------------------------------------------------
    // Core projection logic
    // -------------------------------------------------------------------------

    private ReplayBudgetState buildInitialState(
        AgentBudget budget,
        List<BudgetAllocation> allocations,
        OffsetDateTime at
    ) {
        List<ReplayAllocationState> allocationStates = allocations.stream()
            .map(a -> ReplayAllocationState.builder()
                .category(a.getCategory())
                .limit(a.getTotalLimit())
                .quantitySpent(BigDecimal.ZERO)
                .quantityReserved(BigDecimal.ZERO)
                .available(a.getTotalLimit())
                .enforcementMode(a.getEnforcementMode().name())
                .build())
            .toList();

        return ReplayBudgetState.builder()
            .snapshotAt(at)
            .eventIndex(-1)
            .triggeringEventId(null)
            .totalLimit(budget.getTotalLimit())
            .quantitySpent(BigDecimal.ZERO)
            .quantityReserved(BigDecimal.ZERO)
            .available(budget.getTotalLimit())
            .budgetStatus(budget.getStatus().name())
            .allocations(allocationStates)
            .build();
    }

    private ReplayBudgetState projectStateAfterEvent(
        ReplayBudgetState current,
        SpendEvent event,
        List<BudgetAllocation> allocationConfig
    ) {
        Map<String, ReplayAllocationState> allocationMap = current.getAllocations().stream()
            .collect(Collectors.toMap(ReplayAllocationState::getCategory, a -> a));

        BigDecimal newSpent    = current.getQuantitySpent();
        BigDecimal newReserved = current.getQuantityReserved();
        String category = event.getClaimedCategory();

        switch (event.getDecision()) {
            case AUTHORIZED -> {
                newReserved = newReserved.add(event.getRequestedQuantity());
                if (category != null && allocationMap.containsKey(category)) {
                    ReplayAllocationState a = allocationMap.get(category);
                    BigDecimal newAllocationReserved = a.getQuantityReserved().add(event.getRequestedQuantity());
                    allocationMap.put(category, ReplayAllocationState.builder()
                        .category(a.getCategory())
                        .limit(a.getLimit())
                        .quantitySpent(a.getQuantitySpent())
                        .quantityReserved(newAllocationReserved)
                        .available(a.getLimit().subtract(a.getQuantitySpent()).subtract(newAllocationReserved))
                        .enforcementMode(a.getEnforcementMode())
                        .build());
                }
            }
            case CONFIRMED -> {
                BigDecimal confirmed = event.getConfirmedQuantity() != null
                    ? event.getConfirmedQuantity() : event.getRequestedQuantity();
                newSpent = newSpent.add(confirmed);
                // Only release as much reservation as actually exists — the AUTHORIZED event
                // may be outside the replay window (e.g. seed data with only CONFIRMED events).
                BigDecimal toRelease = newReserved.min(event.getRequestedQuantity());
                newReserved = newReserved.subtract(toRelease);
                if (category != null && allocationMap.containsKey(category)) {
                    ReplayAllocationState a = allocationMap.get(category);
                    BigDecimal newAllocationSpent    = a.getQuantitySpent().add(confirmed);
                    BigDecimal allocationRelease     = a.getQuantityReserved().min(event.getRequestedQuantity());
                    BigDecimal newAllocationReserved = a.getQuantityReserved().subtract(allocationRelease);
                    allocationMap.put(category, ReplayAllocationState.builder()
                        .category(a.getCategory())
                        .limit(a.getLimit())
                        .quantitySpent(newAllocationSpent)
                        .quantityReserved(newAllocationReserved)
                        .available(a.getLimit().subtract(newAllocationSpent).subtract(newAllocationReserved))
                        .enforcementMode(a.getEnforcementMode())
                        .build());
                }
            }
            case FAILED, VOIDED -> {
                BigDecimal toRelease = newReserved.min(event.getRequestedQuantity());
                newReserved = newReserved.subtract(toRelease);
                if (category != null && allocationMap.containsKey(category)) {
                    ReplayAllocationState a = allocationMap.get(category);
                    BigDecimal allocationRelease     = a.getQuantityReserved().min(event.getRequestedQuantity());
                    BigDecimal newAllocationReserved = a.getQuantityReserved().subtract(allocationRelease);
                    allocationMap.put(category, ReplayAllocationState.builder()
                        .category(a.getCategory())
                        .limit(a.getLimit())
                        .quantitySpent(a.getQuantitySpent())
                        .quantityReserved(newAllocationReserved)
                        .available(a.getLimit().subtract(a.getQuantitySpent()).subtract(newAllocationReserved))
                        .enforcementMode(a.getEnforcementMode())
                        .build());
                }
            }
            case DENIED -> {
                // No balance change — DENIED events do not touch reservations
            }
        }

        BigDecimal newAvailable = current.getTotalLimit().subtract(newSpent).subtract(newReserved);

        // Status transitions: EXHAUSTED when available <= 0, otherwise preserve current status
        String newStatus = newAvailable.compareTo(BigDecimal.ZERO) <= 0
            ? "EXHAUSTED"
            : current.getBudgetStatus();

        return ReplayBudgetState.builder()
            .snapshotAt(event.getCreatedAt())
            .eventIndex(current.getEventIndex() + 1)
            .triggeringEventId(event.getId())
            .totalLimit(current.getTotalLimit())
            .quantitySpent(newSpent)
            .quantityReserved(newReserved)
            .available(newAvailable)
            .budgetStatus(newStatus)
            .allocations(new ArrayList<>(allocationMap.values()))
            .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ReplayEventDetail toEventDetail(SpendEvent e, long millisSincePrevious) {
        return ReplayEventDetail.builder()
            .eventId(e.getId())
            .agentId(e.getAgentId())
            .actionType(e.getActionType())
            .description(e.getDescription())
            .requestedQuantity(e.getRequestedQuantity())
            .confirmedQuantity(e.getConfirmedQuantity())
            .currency(e.getCurrency())
            .claimedCategory(e.getClaimedCategory())
            .decision(e.getDecision().name())
            .denialReason(e.getDenialReason())
            .parentEventId(e.getParentEvent() != null ? e.getParentEvent().getId() : null)
            .delegatedTokenId(e.getDelegatedTokenId())
            .createdAt(e.getCreatedAt())
            .confirmedAt(e.getUpdatedAt() != null && e.getDecision() == SpendDecision.CONFIRMED ? e.getUpdatedAt() : null)
            .millisSincePrevious(millisSincePrevious)
            .build();
    }

    private ReplaySummary buildSummary(List<SpendEvent> events) {
        int authorized = 0, denied = 0, confirmed = 0, failed = 0, voided = 0;
        Set<String> agents = new HashSet<>();
        BigDecimal peakReserved = BigDecimal.ZERO;
        OffsetDateTime peakAt = null;
        BigDecimal runningReserved = BigDecimal.ZERO;

        for (SpendEvent e : events) {
            agents.add(e.getAgentId());
            switch (e.getDecision()) {
                case AUTHORIZED -> { authorized++; runningReserved = runningReserved.add(e.getRequestedQuantity()); }
                case DENIED     -> denied++;
                case CONFIRMED  -> { confirmed++; runningReserved = runningReserved.subtract(runningReserved.min(e.getRequestedQuantity())); }
                case FAILED     -> { failed++;    runningReserved = runningReserved.subtract(runningReserved.min(e.getRequestedQuantity())); }
                case VOIDED     -> { voided++;    runningReserved = runningReserved.subtract(runningReserved.min(e.getRequestedQuantity())); }
            }
            if (runningReserved.compareTo(peakReserved) > 0) {
                peakReserved = runningReserved;
                peakAt = e.getCreatedAt();
            }
        }

        return ReplaySummary.builder()
            .totalEvents(events.size())
            .authorizedCount(authorized)
            .deniedCount(denied)
            .confirmedCount(confirmed)
            .failedCount(failed)
            .voidedCount(voided)
            .uniqueAgents(agents.size())
            .peakReservedQuantity(peakReserved)
            .peakReservedAt(peakAt)
            .build();
    }

    private AgentBudget requireBudget(UUID budgetId, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(budgetId)
            .orElseThrow(() -> new BudgetNotFoundException(budgetId));
        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(budgetId);
        }
        return budget;
    }

    private static String encodePageToken(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }

    private static int decodePageToken(String token) {
        if (token == null || token.isBlank()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(token)));
        } catch (Exception e) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Inner class: stateful evaluator for counterfactual replay
    // -------------------------------------------------------------------------

    private static class HypotheticalPolicyEvaluator {

        private final HypotheticalPolicy policy;
        private BigDecimal runningSpent    = BigDecimal.ZERO;
        private BigDecimal runningReserved = BigDecimal.ZERO;
        private final Map<String, BigDecimal> allocationSpent    = new HashMap<>();
        private final Map<String, BigDecimal> allocationReserved = new HashMap<>();
        private String lastDenialReason;

        HypotheticalPolicyEvaluator(HypotheticalPolicy policy) {
            this.policy = policy;
            if (policy != null && policy.getAllocations() != null) {
                for (HypotheticalAllocation a : policy.getAllocations()) {
                    allocationSpent.put(a.getCategory(), BigDecimal.ZERO);
                    allocationReserved.put(a.getCategory(), BigDecimal.ZERO);
                }
            }
        }

        String evaluate(SpendEvent event) {
            lastDenialReason = null;
            if (policy == null) return "AUTHORIZED";

            BigDecimal amount = event.getRequestedQuantity();

            // Check global limit
            if (policy.getTotalLimit() != null) {
                BigDecimal available = policy.getTotalLimit().subtract(runningSpent).subtract(runningReserved);
                if (available.compareTo(amount) < 0) {
                    lastDenialReason = "BUDGET_EXCEEDED";
                    return "DENIED";
                }
            }

            // Check max transaction limit
            if (policy.getMaxTransactionQuantity() != null
                    && amount.compareTo(policy.getMaxTransactionQuantity()) > 0) {
                lastDenialReason = "EXCEEDS_TX_LIMIT";
                return "DENIED";
            }

            // Check allocation limit
            if (policy.getAllocations() != null && event.getClaimedCategory() != null) {
                for (HypotheticalAllocation a : policy.getAllocations()) {
                    if (a.getCategory().equals(event.getClaimedCategory())) {
                        BigDecimal spent    = allocationSpent.getOrDefault(a.getCategory(), BigDecimal.ZERO);
                        BigDecimal reserved = allocationReserved.getOrDefault(a.getCategory(), BigDecimal.ZERO);
                        BigDecimal available = a.getLimit().subtract(spent).subtract(reserved);
                        if (available.compareTo(amount) < 0) {
                            lastDenialReason = "ALLOCATION_EXCEEDED";
                            return "DENIED";
                        }
                    }
                }
            }

            return "AUTHORIZED";
        }

        void advance(SpendEvent event) {
            BigDecimal qty = event.getConfirmedQuantity() != null && event.getDecision() == SpendDecision.CONFIRMED
                ? event.getConfirmedQuantity() : event.getRequestedQuantity();
            // CONFIRMED = finalized spend; AUTHORIZED = in-flight reservation
            if (event.getDecision() == SpendDecision.CONFIRMED) {
                runningSpent = runningSpent.add(qty);
                if (event.getClaimedCategory() != null) {
                    allocationSpent.merge(event.getClaimedCategory(), qty, BigDecimal::add);
                }
            } else {
                runningReserved = runningReserved.add(qty);
                if (event.getClaimedCategory() != null) {
                    allocationReserved.merge(event.getClaimedCategory(), qty, BigDecimal::add);
                }
            }
        }

        String getLastDenialReason() { return lastDenialReason; }
    }
}
