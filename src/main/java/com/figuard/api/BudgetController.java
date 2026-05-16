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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;
    private final LedgerService ledgerService;

    @GetMapping
    public ResponseEntity<Page<BudgetResponse>> listBudgets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) BudgetStatus status,
            @RequestParam(defaultValue = "false") boolean includeCancelled,
            @RequestParam(required = false) String userId) {
        Page<BudgetResponse> budgets = budgetService.listBudgets(
            TenantContext.get(), page, size, status, includeCancelled, userId);
        return ResponseEntity.ok(budgets);
    }

    /**
     * Batch cancel up to 100 budgets in one request. Idempotent — already-terminal budgets
     * are returned without error. Budget IDs not belonging to this tenant are silently ignored.
     */
    @PostMapping("/cancel-batch")
    public ResponseEntity<List<BudgetResponse>> cancelBatch(
            @RequestBody List<UUID> budgetIds) {
        List<BudgetResponse> results = budgetService.cancelBatch(budgetIds, TenantContext.get());
        return ResponseEntity.ok(results);
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        BudgetService.CreateBudgetResult result = budgetService.createBudget(request, TenantContext.get());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.budget());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BudgetResponse> getBudget(@PathVariable UUID id) {
        BudgetResponse response = budgetService.getBudget(id, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<BudgetResponse> updateBudget(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateBudgetRequest request) {
        BudgetResponse response = budgetService.updateBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BudgetResponse> cancelBudget(@PathVariable UUID id) {
        BudgetResponse response = budgetService.cancelBudget(id, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Resume a PAUSED budget. Requires overrideReason (HTTP 400 if blank).
     * Returns HTTP 409 if the budget is not currently PAUSED.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<BudgetResponse> resumeBudget(
            @PathVariable UUID id,
            @Valid @RequestBody ResumeBudgetRequest request) {
        BudgetResponse response = budgetService.resumeBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Extend the budget's expiry window. New expiresAt must be in the future,
     * at most 24h from now, and later than the current expiresAt.
     * Can be called repeatedly to keep a long-running workflow alive.
     */
    @PostMapping("/{id}/extend")
    public ResponseEntity<BudgetResponse> extendBudget(
            @PathVariable UUID id,
            @Valid @RequestBody ExtendBudgetRequest request) {
        BudgetResponse response = budgetService.extendBudget(id, request.getExpiresAt(), TenantContext.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Adjust a budget's totalLimit in-place. Supports CREDIT / DEBIT / RESET / RESET_SPENT.
     * Returns the previous and updated limit alongside the full funding response.
     */
    @PostMapping("/{id}/fund")
    public ResponseEntity<BudgetFundingResponse> fundBudget(
            @PathVariable UUID id,
            @Valid @RequestBody FundBudgetRequest request) {
        BudgetFundingResponse response = budgetService.fundBudget(id, request, TenantContext.get());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/rotate-token")
    public ResponseEntity<Map<String, String>> rotateToken(@PathVariable UUID id) {
        String newToken = budgetService.rotateSessionToken(id, TenantContext.get());
        return ResponseEntity.ok(Map.of("token", newToken));
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<Page<SpendEventResponse>> getLedger(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) SpendDecision decision,
            @RequestParam(required = false) String traceId) {
        Page<SpendEventResponse> ledger = ledgerService.getLedger(id, TenantContext.get(), page, size, decision, traceId);
        return ResponseEntity.ok(ledger);
    }

    @GetMapping("/{id}/tree")
    public ResponseEntity<SpendTreeResponse> getSpendTree(@PathVariable UUID id) {
        SpendTreeResponse tree = ledgerService.getSpendTree(id, TenantContext.get());
        return ResponseEntity.ok(tree);
    }
}
