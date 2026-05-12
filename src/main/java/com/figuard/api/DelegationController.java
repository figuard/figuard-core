package com.figuard.api;

import com.figuard.api.dto.request.CreateDelegationTokenRequest;
import com.figuard.api.dto.response.DelegationTokenResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.DelegationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationService delegationService;

    /**
     * Create a scoped delegation token for a fleet budget.
     *
     * POST /api/v1/budgets/{budgetId}/delegation-tokens
     *
     * Body: { "label": "refund-agent-order-123", "caps": [{"category": "refund", "limit": 3000}] }
     *
     * The raw session_token is returned once and never again. Hand it to the sub-agent
     * immediately; it cannot be retrieved later.
     */
    @PostMapping("/api/v1/budgets/{budgetId}/delegation-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationTokenResponse createToken(
            @PathVariable UUID budgetId,
            @Valid @RequestBody CreateDelegationTokenRequest request) {
        return delegationService.createToken(budgetId, request, TenantContext.get());
    }

    /**
     * List all delegation tokens for a fleet budget.
     *
     * GET /api/v1/budgets/{budgetId}/delegation-tokens
     */
    @GetMapping("/api/v1/budgets/{budgetId}/delegation-tokens")
    public List<DelegationTokenResponse> listTokens(@PathVariable UUID budgetId) {
        return delegationService.listTokens(budgetId, TenantContext.get());
    }

    /**
     * Get a single delegation token by ID.
     *
     * GET /api/v1/delegation-tokens/{tokenId}
     */
    @GetMapping("/api/v1/delegation-tokens/{tokenId}")
    public DelegationTokenResponse getToken(@PathVariable UUID tokenId) {
        return delegationService.getToken(tokenId, TenantContext.get());
    }

    /**
     * Revoke a delegation token immediately.
     *
     * DELETE /api/v1/delegation-tokens/{tokenId}
     *
     * Idempotent — revoking an already-revoked token returns HTTP 200 without error.
     * Fires DELEGATION_TOKEN_REVOKED webhook.
     */
    @DeleteMapping("/api/v1/delegation-tokens/{tokenId}")
    public DelegationTokenResponse revokeToken(@PathVariable UUID tokenId) {
        return delegationService.revokeToken(tokenId, TenantContext.get());
    }
}
