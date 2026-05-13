package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full detail of a single event as it appears in a replay sequence.
 */
@Getter
@Builder
public class ReplayEventDetail {

    private UUID eventId;
    private String agentId;
    private String actionType;
    private String description;
    private BigDecimal requestedQuantity;
    private BigDecimal confirmedQuantity;   // null if not yet confirmed
    private String currency;
    private String claimedCategory;
    private String decision;
    private String denialReason;
    private UUID parentEventId;
    private UUID delegatedTokenId;
    private OffsetDateTime createdAt;
    private OffsetDateTime confirmedAt;
    private long millisSincePrevious;
}
