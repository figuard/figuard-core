package com.figuard.api;

import com.figuard.api.dto.request.CreateEntitlementItemRequest;
import com.figuard.api.dto.request.CreateSubscriptionRequest;
import com.figuard.api.dto.response.EntitlementItemResponse;
import com.figuard.api.dto.response.SubscriptionResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Subscriptions", description = "Manage subscriptions and entitlement items for the known-user billing path. "
        + "A subscription groups one or more entitlement items under an external subscriber identity. "
        + "When a budget is linked to an entitlement item, spend is enforced against the item's per-period limit.")
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // -------------------------------------------------------------------------
    // Subscription CRUD
    // -------------------------------------------------------------------------

    @Operation(summary = "List subscriptions", description = "Returns all subscriptions for this tenant.")
    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> listSubscriptions() {
        return ResponseEntity.ok(subscriptionService.listSubscriptions(TenantContext.get()));
    }

    @Operation(
        summary = "Create a subscription",
        description = """
            Creates a subscription with one or more entitlement items.

            **Idempotent:** if a subscription with the same `externalSubscriberId` already exists
            for this tenant, the existing subscription is returned (HTTP 200).
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "200", description = "Existing subscription returned (idempotent)"),
        @ApiResponse(responseCode = "409", description = "Conflict — subscriber ID exists with different configuration")
    })
    @PostMapping
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.createSubscription(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get subscription by ID")
    @GetMapping("/{subscriptionId}")
    public ResponseEntity<SubscriptionResponse> getSubscription(
            @Parameter(description = "Subscription UUID") @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(subscriptionService.getSubscription(subscriptionId, TenantContext.get()));
    }

    @Operation(summary = "Get subscription by external subscriber ID",
               description = "Look up a subscription using your own system's subscriber identifier.")
    @GetMapping("/by-subscriber/{externalSubscriberId}")
    public ResponseEntity<SubscriptionResponse> getSubscriptionBySubscriberId(
            @PathVariable String externalSubscriberId) {
        return ResponseEntity.ok(
                subscriptionService.getSubscriptionBySubscriberId(externalSubscriberId, TenantContext.get()));
    }

    // -------------------------------------------------------------------------
    // Subscription lifecycle
    // -------------------------------------------------------------------------

    @Operation(summary = "Pause subscription",
               description = "Pauses the subscription. While paused, all authorization attempts for linked budgets are denied with SUBSCRIPTION_PAUSED.")
    @ApiResponse(responseCode = "409", description = "Subscription is not ACTIVE")
    @PostMapping("/{subscriptionId}/pause")
    public ResponseEntity<SubscriptionResponse> pauseSubscription(
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(subscriptionService.pauseSubscription(subscriptionId, TenantContext.get()));
    }

    @Operation(summary = "Resume subscription",
               description = "Resumes a paused subscription. Authorizations are unblocked immediately.")
    @ApiResponse(responseCode = "409", description = "Subscription is not PAUSED")
    @PostMapping("/{subscriptionId}/resume")
    public ResponseEntity<SubscriptionResponse> resumeSubscription(
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(subscriptionService.resumeSubscription(subscriptionId, TenantContext.get()));
    }

    @Operation(summary = "Cancel subscription",
               description = "Cancels the subscription permanently. Cannot be undone. All authorization attempts for linked budgets are denied with SUBSCRIPTION_CANCELLED.")
    @ApiResponse(responseCode = "409", description = "Subscription is already CANCELLED")
    @PostMapping("/{subscriptionId}/cancel")
    public ResponseEntity<SubscriptionResponse> cancelSubscription(
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(subscriptionService.cancelSubscription(subscriptionId, TenantContext.get()));
    }

    // -------------------------------------------------------------------------
    // Entitlement item management
    // -------------------------------------------------------------------------

    @Operation(summary = "List entitlement items", description = "Returns all entitlement items for the given subscription.")
    @GetMapping("/{subscriptionId}/entitlements")
    public ResponseEntity<List<EntitlementItemResponse>> listEntitlementItems(
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(
                subscriptionService.listEntitlementItems(subscriptionId, TenantContext.get()));
    }

    @Operation(summary = "Add entitlement item",
               description = "Adds a new entitlement item to an existing subscription.")
    @ApiResponse(responseCode = "409", description = "An entitlement item with this name already exists on the subscription")
    @PostMapping("/{subscriptionId}/entitlements")
    public ResponseEntity<EntitlementItemResponse> addEntitlementItem(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody CreateEntitlementItemRequest request) {
        EntitlementItemResponse response = subscriptionService.addEntitlementItem(
                subscriptionId, request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get entitlement item")
    @GetMapping("/{subscriptionId}/entitlements/{entitlementItemId}")
    public ResponseEntity<EntitlementItemResponse> getEntitlementItem(
            @PathVariable UUID subscriptionId,
            @PathVariable UUID entitlementItemId) {
        return ResponseEntity.ok(
                subscriptionService.getEntitlementItem(subscriptionId, entitlementItemId, TenantContext.get()));
    }

    @Operation(summary = "Reset entitlement item",
               description = """
                   Manually resets `currentPeriodConsumed` to zero and sets state back to NORMAL.
                   Use this to clear an accidentally over-reported spend, or to grant a free-period exception.
                   Does not affect `nextRenewalAt` — the scheduled renewal will still fire on schedule.
                   """)
    @PostMapping("/{subscriptionId}/entitlements/{entitlementItemId}/reset")
    public ResponseEntity<EntitlementItemResponse> resetEntitlementItem(
            @PathVariable UUID subscriptionId,
            @PathVariable UUID entitlementItemId) {
        return ResponseEntity.ok(
                subscriptionService.resetEntitlementItem(subscriptionId, entitlementItemId, TenantContext.get()));
    }
}
