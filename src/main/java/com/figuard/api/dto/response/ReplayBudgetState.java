package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Projected budget state at a single point in a replay sequence.
 * Computed by applying events from the ledger chronologically — not read from live state.
 */
@Getter
@Builder
public class ReplayBudgetState {

    private OffsetDateTime snapshotAt;
    private int eventIndex;             // 0-based; -1 = initial state before any events
    private UUID triggeringEventId;     // null for initial state

    private BigDecimal totalLimit;
    private BigDecimal quantitySpent;     // sum of all CONFIRMED events so far
    private BigDecimal quantityReserved;  // sum of all outstanding AUTHORIZED events
    private BigDecimal available;         // totalLimit - quantitySpent - quantityReserved
    private String budgetStatus;

    private List<ReplayAllocationState> allocations;
}
