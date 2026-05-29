package com.figuard.api.dto.response;

import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class SpendEventResponse {

    private UUID id;
    private SpendDecision decision;
    private String agentId;
    private String agentType;
    private String actionType;
    private String description;
    private BigDecimal requestedQuantity;
    private BigDecimal confirmedQuantity;   // null until /confirm is called
    private String currency;

    // The real-world entity this spend relates to
    private String entityId;

    // Enforcement fields — what the agent declared
    private String claimedCategory;
    private String claimedItemType;

    // Audit only — never enforcement
    private String intentContext;

    private String idempotencyKey;
    private DenialCode denialReason;
    private String failureReason;
    private UUID parentEventId;
    private UUID chainRootEventId;
    private String traceId;
    private OffsetDateTime createdAt;
    private Map<String, Object> metadata;

    // Set only on external events (recorded via POST /events/external).
    // Null for standard agent-initiated events.
    private String eventSource;
    private OffsetDateTime occurredAt;
}
