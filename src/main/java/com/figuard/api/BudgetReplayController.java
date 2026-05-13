package com.figuard.api;

import com.figuard.api.dto.request.CounterfactualReplayRequest;
import com.figuard.api.dto.response.*;
import com.figuard.security.TenantContext;
import com.figuard.service.ReplayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/budgets/{budgetId}/replay")
@RequiredArgsConstructor
public class BudgetReplayController {

    private final ReplayService replayService;

    /**
     * Full budget replay — every event in chronological order with projected state after each.
     * Pure read; no effect on budget state.
     */
    @GetMapping
    public ResponseEntity<BudgetReplayResponse> getFullReplay(
        @PathVariable UUID budgetId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until,
        @RequestParam(defaultValue = "true")  boolean includeDenied,
        @RequestParam(defaultValue = "true")  boolean includeStateSnapshots,
        @RequestParam(defaultValue = "100")   int pageSize,
        @RequestParam(required = false)       String pageToken
    ) {
        int clampedPageSize = Math.min(pageSize, 500);
        BudgetReplayResponse response = replayService.replay(
            budgetId, from, until, includeDenied, includeStateSnapshots,
            clampedPageSize, pageToken, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Point-in-time state — projects budget state to a specific timestamp.
     * Does not return the event list, only the resulting state.
     */
    @GetMapping("/state")
    public ResponseEntity<PointInTimeStateResponse> getStateAt(
        @PathVariable UUID budgetId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at
    ) {
        PointInTimeStateResponse response = replayService.getStateAt(budgetId, at, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Lightweight timeline — events in chronological order without state snapshots.
     */
    @GetMapping("/timeline")
    public ResponseEntity<TimelineResponse> getTimeline(
        @PathVariable UUID budgetId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until
    ) {
        TimelineResponse response = replayService.getTimeline(budgetId, from, until, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Counterfactual replay — runs actual AUTHORIZED events through a hypothetical policy.
     * Shows which transactions would have been denied under different limits.
     * Accepts either an inline hypothetical_policy or a manifest_version reference.
     */
    @PostMapping("/counterfactual")
    public ResponseEntity<CounterfactualReplayResponse> getCounterfactual(
        @PathVariable UUID budgetId,
        @Valid @RequestBody CounterfactualReplayRequest request
    ) {
        CounterfactualReplayResponse response = replayService.replayCounterfactual(
            budgetId, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }
}
