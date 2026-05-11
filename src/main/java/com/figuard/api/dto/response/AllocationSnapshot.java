package com.figuard.api.dto.response;

import com.figuard.domain.enums.AllocationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

// Point-in-time snapshot of allocation state captured at authorization decision time.
// Present on DENIED responses when the denial was allocation-specific.
@Getter
@Builder
public class AllocationSnapshot {

    private String category;
    private BigDecimal limit;
    private BigDecimal quantitySpent;
    private BigDecimal quantityReserved;
    private BigDecimal availableQuantity;
    private AllocationStatus status;
}
