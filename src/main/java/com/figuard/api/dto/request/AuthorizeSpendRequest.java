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

    @NotNull(message = "requestedAmount is required")
    @PositiveOrZero(message = "requestedAmount must be zero or positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal requestedAmount;

    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    private String currency = "USD";

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

    private Map<String, Object> metadata;
}
