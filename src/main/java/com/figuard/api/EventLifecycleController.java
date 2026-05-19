package com.figuard.api;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.security.TenantContext;
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
}
