package com.figuard.api;

import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.request.ExtendBudgetRequest;
import com.figuard.api.dto.request.FundBudgetRequest;
import com.figuard.api.dto.request.ResumeBudgetRequest;
import com.figuard.api.dto.request.UpdateBudgetRequest;
import com.figuard.api.dto.response.BudgetFundingResponse;
import com.figuard.api.dto.response.BudgetResponse;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.api.dto.response.SpendTreeResponse;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.security.TenantContext;
import com.figuard.service.BudgetService;
import com.figuard.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Budgets", description = "Create and manage agent budgets. A budget is the authorization envelope your agent operates within — it holds the total limit, allocations, and session token the agent uses to request spend.")
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;
    private final LedgerService ledgerService;

    @Operation(summary = "List budgets", description = "Returns all budgets for this tenant, newest first. Filter by status or userId. CANCELLED budgets are excluded by default — set includeCancelled=true to include them.")
    @GetMapping
    public ResponseEntity<Page<BudgetResponse>> listBudgets(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Filter by status") @RequestParam(required = false) BudgetStatus status,
            @Parameter(description = "Include CANCELLED budgets") @RequestParam(defaultValue = "false") boolean includeCancelled,
            @Parameter(description = "Filter by userId") @RequestParam(required = false) String userId) {
        Page<BudgetResponse> budgets = budgetService.listBudgets(
            TenantContext.get(), page, size, status, includeCancelled, userId);
        return ResponseEntity.ok(budgets);
    }

    @Operation(
        summary = "Create a budget",
        description = """
            Create a budget for an agent session. Returns the budget and a one-time `sessionToken` (`st_` prefix) to hand to the agent.

            **Idempotent:** if `externalReference` is provided and a live budget with that reference already exists, the existing budget is returned (HTTP 200). If the payload conflicts with the existing budget, HTTP 409 is returned.

            **Allocations:** optional. If omitted, the agent can spend freely up to `totalLimit` with no category restrictions. If provided, every authorize call must match an allocation by `category`.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Budget created"),
        @ApiResponse(responseCode = "200", description = "Idempotent hit — existing budget returned"),
        @ApiResponse(responseCode = "409", description = "Idempotency conflict — same externalReference, different payload")
    })
    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        BudgetService.CreateBudgetResult result = budgetService.createBudget(request, TenantContext.get());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.budget());
    }

    @Operation(summary = "Get a budget", description = "Fetch a single budget by ID. Returns 404 if not found or not owned by this tenant.")
    @GetMapping("/{id}")
    public ResponseEntity<BudgetResponse> getBudget(@PathVariable UUID id) {
        BudgetResponse response = budgetService.getBudget(id, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update a budget", description = "Update mutable fields on an active budget (metadata, webhookUrl, velocityControls). Returns 409 if the budget is in a terminal state.")
    @PatchMapping("/{id}")
    public ResponseEntity<BudgetResponse> updateBudget(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateBudgetRequest request) {
        BudgetResponse response = budgetService.updateBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Cancel a budget", description = "Cancel an active or paused budget. Idempotent — cancelling an already-terminal budget returns the current state without error.")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BudgetResponse> cancelBudget(@PathVariable UUID id) {
        BudgetResponse response = budgetService.cancelBudget(id, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Cancel budgets in bulk",
        description = "Cancel up to 100 budgets in one request. Idempotent — already-terminal budgets are returned without error. Budget IDs not belonging to this tenant are silently ignored."
    )
    @PostMapping("/cancel-batch")
    public ResponseEntity<List<BudgetResponse>> cancelBatch(
            @RequestBody List<UUID> budgetIds) {
        List<BudgetResponse> results = budgetService.cancelBatch(budgetIds, TenantContext.get());
        return ResponseEntity.ok(results);
    }

    @Operation(
        summary = "Resume a paused budget",
        description = "Resume a PAUSED budget. `overrideReason` is required for the audit trail. Returns 400 if blank, 409 if the budget is not currently PAUSED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Budget resumed"),
        @ApiResponse(responseCode = "400", description = "overrideReason is blank"),
        @ApiResponse(responseCode = "409", description = "Budget is not in PAUSED state")
    })
    @PostMapping("/{id}/resume")
    public ResponseEntity<BudgetResponse> resumeBudget(
            @PathVariable UUID id,
            @Valid @RequestBody ResumeBudgetRequest request) {
        BudgetResponse response = budgetService.resumeBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Extend a budget's expiry",
        description = "Move the budget's `expiresAt` forward. The new value must be in the future, later than the current `expiresAt`, and at most 24h from now. Can be called repeatedly to keep a long-running workflow alive."
    )
    @PostMapping("/{id}/extend")
    public ResponseEntity<BudgetResponse> extendBudget(
            @PathVariable UUID id,
            @Valid @RequestBody ExtendBudgetRequest request) {
        BudgetResponse response = budgetService.extendBudget(id, request.getExpiresAt(), TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Fund (adjust) a budget's limit",
        description = """
            Adjust a budget's `totalLimit` in-place without creating a new budget or invalidating the session token.

            **Operations:**
            - `CREDIT` — add to totalLimit
            - `DEBIT` — subtract from totalLimit (rejected if it would drop below spent amount)
            - `RESET` — set totalLimit to the specified amount
            - `RESET_SPENT` — zero out spent amount (emergency reset)

            Returns the previous limit, updated limit, and full budget state.
            """
    )
    @PostMapping("/{id}/fund")
    public ResponseEntity<BudgetFundingResponse> fundBudget(
            @PathVariable UUID id,
            @Valid @RequestBody FundBudgetRequest request) {
        BudgetFundingResponse response = budgetService.fundBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Rotate the session token",
        description = """
            Issue a new `st_` session token and invalidate the old one. The old token remains valid for a short grace period (configurable, default 60 seconds) to avoid in-flight failures during token handoff.

            The new raw token is returned once — store it before the response is gone.
            """
    )
    @PostMapping("/{id}/rotate-token")
    public ResponseEntity<Map<String, String>> rotateToken(@PathVariable UUID id) {
        String newToken = budgetService.rotateSessionToken(id, TenantContext.get());
        return ResponseEntity.ok(Map.of("sessionToken", newToken));
    }

    @Operation(
        summary = "Get the spend ledger",
        description = "Paginated list of spend events for a budget, newest first. Filter by decision (AUTHORIZED/DENIED) or traceId."
    )
    @GetMapping("/{id}/ledger")
    public ResponseEntity<Page<SpendEventResponse>> getLedger(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Filter by decision") @RequestParam(required = false) SpendDecision decision,
            @Parameter(description = "Filter by traceId") @RequestParam(required = false) String traceId) {
        Page<SpendEventResponse> ledger = ledgerService.getLedger(id, TenantContext.get(), page, size, decision, traceId);
        return ResponseEntity.ok(ledger);
    }

    @Operation(
        summary = "Get the spend tree",
        description = "Returns a hierarchical view of all spend events grouped by allocation category. Useful for understanding how budget was distributed across resource types."
    )
    @GetMapping("/{id}/tree")
    public ResponseEntity<SpendTreeResponse> getSpendTree(@PathVariable UUID id) {
        SpendTreeResponse tree = ledgerService.getSpendTree(id, TenantContext.get());
        return ResponseEntity.ok(tree);
    }

}
