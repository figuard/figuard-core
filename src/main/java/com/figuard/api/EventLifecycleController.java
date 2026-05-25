package com.figuard.api;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.request.VoidTreeRequest;
import com.figuard.api.dto.response.ChainDetailResponse;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.dto.response.VoidTreeResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.LedgerService;
import com.figuard.service.PaymentLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Event Lifecycle", description = "Close out authorized spend events. Every AUTHORIZED event from `/authorize` must be resolved — confirmed (actual spend committed), failed (action failed, reservation released), or voided (action cancelled).")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventLifecycleController {

    private final PaymentLifecycleService lifecycleService;
    private final LedgerService ledgerService;

    @Operation(
        summary = "Confirm an event",
        description = "Commit the actual spend amount against the budget. `confirmedQuantity` can differ from the reserved amount — FiGuard will reconcile the difference. Returns the finalized event."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event confirmed"),
        @ApiResponse(responseCode = "404", description = "Event not found or not owned by this tenant"),
        @ApiResponse(responseCode = "409", description = "Event is already in a terminal state")
    })
    @PostMapping("/{id}/confirm")
    public ResponseEntity<SpendEventResponse> confirmEvent(
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmEventRequest request) {
        return ResponseEntity.ok(lifecycleService.confirmEvent(id, request, TenantContext.get()));
    }

    @Operation(
        summary = "Fail an event",
        description = "Mark the event as failed and release the reserved amount back to the budget. Use this when the agent's action did not execute (e.g. payment declined, API error)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event marked failed, reservation released"),
        @ApiResponse(responseCode = "409", description = "Event is already in a terminal state")
    })
    @PostMapping("/{id}/fail")
    public ResponseEntity<SpendEventResponse> failEvent(
            @PathVariable UUID id,
            @Valid @RequestBody FailEventRequest request) {
        return ResponseEntity.ok(lifecycleService.failEvent(id, request, TenantContext.get()));
    }

    @Operation(
        summary = "Void an event",
        description = "Cancel the event and release the reservation. Use this when the action was intentionally cancelled by the user or orchestrator (not a failure)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event voided, reservation released"),
        @ApiResponse(responseCode = "409", description = "Event is already in a terminal state")
    })
    @PostMapping("/{id}/void")
    public ResponseEntity<SpendEventResponse> voidEvent(
            @PathVariable UUID id,
            @Valid @RequestBody VoidEventRequest request) {
        return ResponseEntity.ok(lifecycleService.voidEvent(id, request, TenantContext.get()));
    }

    @Operation(
        summary = "Void an entire causal subtree",
        description = """
            Atomically void the target event and every AUTHORIZED descendant in its causal chain.
            Use this when an orchestration job is cancelled or fails and you want to release all
            child reservations in a single call — rather than voiding each agent's event individually.

            CONFIRMED and already-VOIDED descendants are left untouched.
            Any descendant with an `externalTransactionId` (a committed payment) will cause the
            entire operation to fail — those events must be refunded before the tree can be voided.

            Returns a summary: total events voided, total quantity released, and the full list of voided IDs.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subtree voided, all reservations released"),
        @ApiResponse(responseCode = "404", description = "Root event not found or not owned by this tenant"),
        @ApiResponse(responseCode = "409", description = "Root event is not AUTHORIZED, or a descendant requires a refund before voiding")
    })
    @PostMapping("/{id}/void-tree")
    public ResponseEntity<VoidTreeResponse> voidTree(
            @PathVariable UUID id,
            @Valid @RequestBody VoidTreeRequest request) {
        return ResponseEntity.ok(lifecycleService.voidTree(id, request, TenantContext.get()));
    }

    @Operation(
        summary = "Get causal chain detail",
        description = "Returns the full causal chain rooted at the given event, including chain-level spend totals, cap metadata, and the nested event tree."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Chain detail returned"),
        @ApiResponse(responseCode = "404", description = "Chain root event not found or not owned by this tenant")
    })
    @GetMapping("/{id}/chain")
    public ResponseEntity<ChainDetailResponse> getChain(@PathVariable UUID id) {
        return ResponseEntity.ok(ledgerService.getChainDetail(id, TenantContext.get()));
    }
}
