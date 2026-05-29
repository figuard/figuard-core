package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
public class RecordExternalEventRequest {

    /**
     * The budget this event should be charged against.
     */
    @NotNull
    private UUID budgetId;

    /**
     * Identifier for who performed the action (e.g. "finance_manager_u123", "ops_bot").
     */
    @NotBlank
    private String agentId;

    /**
     * Action type label (e.g. "PAYMENT", "REFUND", "TRANSFER").
     */
    @NotBlank
    private String actionType;

    @NotBlank
    @Size(max = 1000)
    private String description;

    /**
     * Actual quantity spent. Charged directly to quantitySpent — no reservation step.
     */
    @NotNull
    @Positive
    private BigDecimal quantity;

    /**
     * Idempotency key to prevent duplicate recording. Recommended: use the
     * external system's transaction ID (e.g. the QuickBooks transaction ID).
     */
    @NotBlank
    private String idempotencyKey;

    /**
     * Optional category (e.g. "vendor_payment"). For audit and reporting only — not
     * enforced against allocations (the action already happened).
     */
    private String claimedCategory;

    /**
     * Who triggered this event: "HUMAN" for a person acting outside FiGuard,
     * "EXTERNAL" for an automated system not going through the authorize endpoint.
     * Defaults to "EXTERNAL" if not provided.
     */
    private String source;

    /**
     * When the action occurred in the real world. Defaults to now if omitted.
     * Use this to backdate events recorded after the fact (e.g. end-of-day reconciliation).
     */
    private OffsetDateTime occurredAt;

    /**
     * Arbitrary metadata to attach for audit or downstream consumers.
     */
    private Map<String, Object> metadata;
}
