package com.figuard.api;

import com.figuard.api.dto.request.CounterfactualReplayRequest;
import com.figuard.api.dto.response.*;
import com.figuard.security.TenantContext;
import com.figuard.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Tag(name = "Replay & Audit", description = "Read-only audit tools. Replay the full event history of a budget, project state at any point in time, or run counterfactual analysis — 'what would have been denied under a tighter policy?'")
@RestController
@RequestMapping("/api/v1/budgets/{budgetId}/replay")
@RequiredArgsConstructor
public class BudgetReplayController {

    private final ReplayService replayService;

    @Operation(
        summary = "Full replay",
        description = "Every event in chronological order with projected budget state after each one. Pure read — no effect on budget state. Supports time-range filtering and cursor-based pagination (max 500 events per page)."
    )
    @GetMapping
    public ResponseEntity<BudgetReplayResponse> getFullReplay(
        @PathVariable UUID budgetId,
        @Parameter(description = "Start of time range (ISO 8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @Parameter(description = "End of time range (ISO 8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until,
        @Parameter(description = "Include DENIED events") @RequestParam(defaultValue = "true") boolean includeDenied,
        @Parameter(description = "Include budget state snapshot after each event") @RequestParam(defaultValue = "true") boolean includeStateSnapshots,
        @Parameter(description = "Page size (max 500)") @RequestParam(defaultValue = "100") int pageSize,
        @Parameter(description = "Cursor from previous page") @RequestParam(required = false) String pageToken
    ) {
        int clampedPageSize = Math.min(pageSize, 500);
        BudgetReplayResponse response = replayService.replay(
            budgetId, from, until, includeDenied, includeStateSnapshots,
            clampedPageSize, pageToken, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Point-in-time state",
        description = "Project the budget's state at an exact timestamp by replaying all events up to that point. Returns only the resulting state — not the event list. Useful for reconstructing 'what did this budget look like at 14:32 yesterday?'"
    )
    @GetMapping("/state")
    public ResponseEntity<PointInTimeStateResponse> getStateAt(
        @PathVariable UUID budgetId,
        @Parameter(description = "Timestamp to project state to (ISO 8601)", required = true) @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at
    ) {
        PointInTimeStateResponse response = replayService.getStateAt(budgetId, at, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Timeline",
        description = "Lightweight event list in chronological order — no state snapshots. Faster than full replay when you only need to see what happened, not the projected balance after each event."
    )
    @GetMapping("/timeline")
    public ResponseEntity<TimelineResponse> getTimeline(
        @PathVariable UUID budgetId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until
    ) {
        TimelineResponse response = replayService.getTimeline(budgetId, from, until, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Counterfactual replay",
        description = """
            Run actual AUTHORIZED events through a hypothetical policy to see which would have been denied.

            Use this to answer: *"If we had set a $500 limit instead of $1000, which transactions would have been blocked?"*

            Accepts either an inline `hypotheticalPolicy` object or a `manifestVersion` reference. Returns each event annotated with the counterfactual decision.
            """
    )
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
