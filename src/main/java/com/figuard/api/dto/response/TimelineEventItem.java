package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Lightweight event row for the timeline endpoint — no state snapshots. */
@Getter
@Builder
public class TimelineEventItem {

    private int eventIndex;
    private UUID eventId;
    private String agentId;
    private String decision;
    private BigDecimal requestedQuantity;
    private String claimedCategory;
    private String description;
    private OffsetDateTime createdAt;
    private long millisSincePrevious;
}
