package com.figuard.api;

import com.figuard.api.dto.request.CreateDelegationTokenRequest;
import com.figuard.api.dto.response.DelegationTokenResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.DelegationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Delegation", description = "Issue scoped sub-agent tokens from a fleet budget. Delegation lets an orchestrator carve out a capped slice of budget and hand it to a sub-agent as a standalone session token — the sub-agent can never exceed its cap or see the parent budget.")
@RestController
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationService delegationService;

    @Operation(
        summary = "Create a delegation token",
        description = """
            Issue a new scoped session token (`st_` prefix) for a sub-agent. The token is bounded by `caps` — per-category spend limits that sit below the parent budget's allocation.

            **The raw token is returned once and never again.** Hand it to the sub-agent immediately; it cannot be retrieved later. Store only the `id` if you need to track or revoke it.

            Fires `DELEGATION_TOKEN_CREATED` webhook.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Delegation token created"),
        @ApiResponse(responseCode = "404", description = "Budget not found or not owned by this tenant"),
        @ApiResponse(responseCode = "409", description = "Budget is not in ACTIVE state")
    })
    @PostMapping("/api/v1/budgets/{budgetId}/delegation-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationTokenResponse createToken(
            @PathVariable UUID budgetId,
            @Valid @RequestBody CreateDelegationTokenRequest request) {
        return delegationService.createToken(budgetId, request, TenantContext.get());
    }

    @Operation(
        summary = "List delegation tokens",
        description = "List all delegation tokens issued for a budget. Raw token values are never returned here — only metadata."
    )
    @GetMapping("/api/v1/budgets/{budgetId}/delegation-tokens")
    public List<DelegationTokenResponse> listTokens(@PathVariable UUID budgetId) {
        return delegationService.listTokens(budgetId, TenantContext.get());
    }

    @Operation(summary = "Get a delegation token", description = "Fetch a single delegation token by its ID.")
    @GetMapping("/api/v1/delegation-tokens/{tokenId}")
    public DelegationTokenResponse getToken(@PathVariable UUID tokenId) {
        return delegationService.getToken(tokenId, TenantContext.get());
    }

    @Operation(
        summary = "Revoke a delegation token",
        description = "Revoke a delegation token immediately. Any in-flight authorize calls using this token will be denied. Idempotent — revoking an already-revoked token returns HTTP 200. Fires `DELEGATION_TOKEN_REVOKED` webhook."
    )
    @DeleteMapping("/api/v1/delegation-tokens/{tokenId}")
    public DelegationTokenResponse revokeToken(@PathVariable UUID tokenId) {
        return delegationService.revokeToken(tokenId, TenantContext.get());
    }
}
