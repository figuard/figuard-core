package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/** A single event whose hypothetical decision differs from the actual decision. */
@Getter
@Builder
public class CounterfactualDelta {

    private UUID eventId;
    private String actualDecision;
    private String hypotheticalDecision;
    private String hypotheticalDenialReason;
    private BigDecimal requestedQuantity;
    private String agentId;
    private String description;
    private String claimedCategory;
}
