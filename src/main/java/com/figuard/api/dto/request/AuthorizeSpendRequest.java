package com.figuard.api.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
public class AuthorizeSpendRequest {

    @NotBlank(message = "agentId is required")
    private String agentId;

    private String agentType;

    @NotBlank(message = "actionType is required")
    private String actionType;

    @NotBlank(message = "description is required")
    @Size(max = 1000)
    private String description;

    @NotNull(message = "requestedQuantity is required")
    @PositiveOrZero(message = "requestedQuantity must be zero or positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal requestedQuantity;

    // Required for monetary budgets — must match budget currency.
    // Omit or null for resource budgets (tokens, api_calls, etc.).
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    private String currency;

    // Required when budget has allocations — enforced in service, not here.
    // Agents explicitly declare what category this spend belongs to.
    private String claimedCategory;

    // Optional — checked against forbiddenItemTypes only when enforcementMode = STRICT
    private String claimedItemType;

    // The real-world entity this spend relates to — invoice_123, order_456, booking_789.
    // Optional. Makes deduplication and entity-scoped queries possible without
    // encoding entity context into the idempotency key.
    private String entityId;

    // Audit/logging only — NEVER used for enforcement decisions
    @Size(max = 1000)
    private String intentContext;

    private UUID parentEventId;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    // Optional. Links all authorize calls from a single agent run together.
    // Callers supply their own run ID — any opaque string (UUID, span ID, etc.)
    // Filterable on the ledger: GET /api/v1/budgets/{id}/ledger?traceId=...
    private String traceId;

    private Map<String, Object> metadata;

    // Optional per-chain spend cap — only meaningful on root authorize() calls
    // (i.e. when parentEventId is null). When set, the total AUTHORIZED + CONFIRMED
    // spend across the entire causal chain rooted at this event is checked against
    // this ceiling on every subsequent child authorization.
    // Denied child requests receive SUBTREE_CAP_EXCEEDED with the remaining capacity.
    @DecimalMin(value = "0.0", inclusive = false, message = "maxSubtreeQuantity must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal maxSubtreeQuantity;

    // When true, all enforcement checks run and a full AUTHORIZED/DENIED response
    // is returned, but nothing is written to the ledger and no webhooks fire.
    // Use during integration testing to verify enforcement logic without creating records.
    private boolean dryRun;
}
