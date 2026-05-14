package com.figuard.api.dto.response;

import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.SpendDecision;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class AuthorizationResponse {

    private UUID eventId;
    private SpendDecision decision;

    // Set when decision = AUTHORIZED
    private BigDecimal approvedQuantity;
    private OffsetDateTime authorizedAt;

    // Set when decision = DENIED
    private DenialCode denialReason;
    private String denialMessage;           // actionable human-readable explanation

    // Present on allocation-specific denials (ALLOCATION_EXHAUSTED, NO_MATCHING_ALLOCATION,
    // FORBIDDEN_ITEM_TYPE, MISSING_CLAIMED_CATEGORY)
    private AllocationSnapshot allocationSnapshot;

    // Present on ENTITY_ALREADY_AUTHORIZED — points caller to the existing event they can void or confirm
    private UUID originalEventId;

    // Always present
    private BudgetSnapshot budgetSnapshot;

    // Request trace ID — correlates with X-Trace-Id response header and server logs
    private String traceId;
}
