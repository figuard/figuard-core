package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A spend event node in the causal chain tree.
 * Children are events that declared this event as their parentEventId.
 * Null children list means no children (leaf node).
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpendTreeNode {

    private UUID id;
    private SpendDecision decision;
    private String agentId;
    private String agentType;
    private String actionType;
    private String description;
    private BigDecimal requestedQuantity;
    private BigDecimal confirmedQuantity;
    private String currency;
    private String entityId;
    private String claimedCategory;
    private String claimedItemType;
    private String intentContext;
    private String idempotencyKey;
    private DenialCode denialReason;
    private String failureReason;
    private UUID parentEventId;
    private OffsetDateTime createdAt;
    private Map<String, Object> metadata;

    // Nested children — events that cited this event as their parentEventId.
    // Empty list = leaf node. Present only when children exist.
    private List<SpendTreeNode> children;
}
