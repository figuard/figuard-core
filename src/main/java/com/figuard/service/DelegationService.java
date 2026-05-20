package com.figuard.service;

import com.figuard.api.dto.request.CreateDelegationTokenRequest;
import com.figuard.api.dto.response.DelegationTokenAllocationResponse;
import com.figuard.api.dto.response.DelegationTokenResponse;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.DelegatedToken;
import com.figuard.domain.entity.DelegatedTokenAllocation;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.enums.DelegatedTokenStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.DelegatedTokenRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DelegationService {

    private final AgentBudgetRepository budgetRepository;
    private final DelegatedTokenRepository delegatedTokenRepository;
    private final SessionTokenService sessionTokenService;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookPayloadBuilder webhookPayloadBuilder;

    /**
     * Create a new scoped delegation token for a fleet budget.
     *
     * The raw session token is returned exactly once in the response — it is never
     * stored and cannot be retrieved again. The caller must hand it to the sub-agent
     * immediately and store it securely for the duration of the agent's work.
     *
     * @throws ResponseStatusException HTTP 404 if budget not found
     * @throws ResponseStatusException HTTP 403 if budget belongs to a different tenant
     * @throws ResponseStatusException HTTP 409 if budget is CANCELLED
     */
    @Transactional
    public DelegationTokenResponse createToken(UUID budgetId,
                                               CreateDelegationTokenRequest request,
                                               Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(budgetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Budget does not belong to this tenant");
        }

        if (budget.getStatus() == BudgetStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot create a delegation token for a CANCELLED budget");
        }

        // Label idempotency: if an ACTIVE token with this label already exists on the budget,
        // return it. Session token is not re-issued (caller must have stored it at creation).
        if (request.getLabel() != null && !request.getLabel().isBlank()) {
            Optional<DelegatedToken> existing = delegatedTokenRepository
                .findActiveByBudgetIdAndLabel(budgetId, request.getLabel());
            if (existing.isPresent()) {
                log.info("DelegatedToken idempotent hit: id={} parentBudgetId={} label={}",
                    existing.get().getId(), budgetId, request.getLabel());
                return toResponse(existing.get(), null);
            }
        }

        String rawToken = sessionTokenService.generateToken();
        String tokenHash = sessionTokenService.hashToken(rawToken);
        String prefix = sessionTokenService.extractPrefix(rawToken);

        DelegatedToken token = new DelegatedToken();
        token.setParentBudget(budget);
        token.setTenant(budget.getTenant());
        token.setSessionTokenHash(tokenHash);
        token.setSessionTokenPrefix(prefix);
        token.setLabel(request.getLabel());

        List<DelegatedTokenAllocation> caps = request.getCaps().stream().map(c -> {
            DelegatedTokenAllocation alloc = new DelegatedTokenAllocation();
            alloc.setDelegatedToken(token);
            alloc.setCategory(c.getCategory().toLowerCase());
            alloc.setTotalLimit(c.getLimit());
            return alloc;
        }).collect(Collectors.toList());
        token.setCaps(caps);

        DelegatedToken saved = delegatedTokenRepository.save(token);

        log.info("DelegatedToken created: id={} parentBudgetId={} label={}",
            saved.getId(), budgetId, request.getLabel());

        return toResponse(saved, rawToken);
    }

    /**
     * Get a delegation token by ID.
     * The session token is never returned — only the prefix is visible.
     */
    @Transactional(readOnly = true)
    public DelegationTokenResponse getToken(UUID tokenId, Tenant tenant) {
        DelegatedToken token = delegatedTokenRepository.findById(tokenId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Delegation token not found"));
        if (!token.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Delegation token does not belong to this tenant");
        }
        return toResponse(token, null);
    }

    /**
     * List all delegation tokens for a fleet budget.
     */
    @Transactional(readOnly = true)
    public List<DelegationTokenResponse> listTokens(UUID budgetId, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(budgetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Budget does not belong to this tenant");
        }
        return delegatedTokenRepository.findByParentBudgetId(budgetId)
            .stream().map(t -> toResponse(t, null)).collect(Collectors.toList());
    }

    /**
     * Revoke a delegation token immediately.
     *
     * Any subsequent authorize call using this token will receive INVALID_SESSION_TOKEN.
     * Already-authorized events are not affected — their lifecycle (confirm/fail/void)
     * continues normally.
     *
     * Idempotent: revoking an already-revoked token returns the current state without error.
     */
    @Transactional
    public DelegationTokenResponse revokeToken(UUID tokenId, Tenant tenant) {
        DelegatedToken token = delegatedTokenRepository.findByIdWithLock(tokenId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Delegation token not found"));
        if (!token.getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Delegation token does not belong to this tenant");
        }

        if (token.getStatus() == DelegatedTokenStatus.REVOKED) {
            return toResponse(token, null);  // idempotent
        }

        token.setStatus(DelegatedTokenStatus.REVOKED);
        token.setRevokedAt(OffsetDateTime.now());
        DelegatedToken saved = delegatedTokenRepository.save(token);

        log.info("DelegatedToken revoked: id={} parentBudgetId={}",
            saved.getId(), saved.getParentBudget().getId());

        webhookDispatcher.dispatch(tenant.getId(), WebhookEventType.DELEGATION_TOKEN_REVOKED,
            webhookPayloadBuilder.buildDelegationTokenRevokedPayload(saved));

        return toResponse(saved, null);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private DelegationTokenResponse toResponse(DelegatedToken token, String rawToken) {
        List<DelegationTokenAllocationResponse> capResponses = token.getCaps() == null
            ? List.of()
            : token.getCaps().stream()
                .map(c -> DelegationTokenAllocationResponse.builder()
                    .id(c.getId())
                    .category(c.getCategory())
                    .totalLimit(c.getTotalLimit())
                    .quantitySpent(c.getQuantitySpent())
                    .quantityReserved(c.getQuantityReserved())
                    .availableQuantity(c.availableQuantity())
                    .build())
                .collect(Collectors.toList());

        return DelegationTokenResponse.builder()
            .id(token.getId())
            .parentBudgetId(token.getParentBudget().getId())
            .label(token.getLabel())
            .status(token.getStatus().name())
            .sessionToken(rawToken)
            .sessionTokenPrefix(token.getSessionTokenPrefix())
            .caps(capResponses)
            .revokedAt(token.getRevokedAt())
            .createdAt(token.getCreatedAt())
            .build();
    }
}
