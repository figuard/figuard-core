package com.figuard.api;

import com.figuard.api.dto.request.CreateBudgetRequest;
import com.figuard.api.dto.request.ResumeBudgetRequest;
import com.figuard.api.dto.request.UpdateBudgetRequest;
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
            @RequestParam(required = false) BudgetStatus status) {
        Page<BudgetResponse> budgets = budgetService.listBudgets(TenantContext.get(), page, size, status);
        return ResponseEntity.ok(budgets);
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        BudgetResponse response = budgetService.createBudget(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

    @PostMapping("/{id}/rotate-token")
    public ResponseEntity<Map<String, String>> rotateToken(@PathVariable UUID id) {
        String newToken = budgetService.rotateSessionToken(id, TenantContext.get());
        return ResponseEntity.ok(Map.of("sessionToken", newToken));
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
