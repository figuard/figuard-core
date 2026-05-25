package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full causal chain rooted at a specific event, with chain-level metadata.
 * Returned by GET /api/v1/events/{chainRootEventId}/chain.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChainDetailResponse {

    private UUID chainRootEventId;
    private UUID budgetId;

    // Chain cap — null if no maxSubtreeQuantity was set on the root event
    private BigDecimal maxSubtreeQuantity;
    private BigDecimal totalChainSpend;      // AUTHORIZED + CONFIRMED in this chain
    private BigDecimal chainCapRemaining;    // maxSubtreeQuantity - totalChainSpend; null if uncapped

    private String currency;
    private int totalEvents;
    private BigDecimal totalAuthorized;      // AUTHORIZED + CONFIRMED
    private BigDecimal totalConfirmed;       // CONFIRMED only

    private OffsetDateTime chainStartedAt;   // createdAt of root event
    private OffsetDateTime lastActivityAt;   // createdAt of most recent event in chain

    // Full tree — root event with nested children
    private List<SpendTreeNode> roots;
}
