package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Per-allocation state at a single point in a budget replay.
 * Distinct from AllocationSnapshot (which captures state at authorization decision time).
 */
@Getter
@Builder
public class ReplayAllocationState {

    private String category;
    private BigDecimal limit;
    private BigDecimal quantitySpent;       // cumulative CONFIRMED against this allocation
    private BigDecimal quantityReserved;    // outstanding AUTHORIZED not yet confirmed
    private BigDecimal available;
    private String enforcementMode;
}
