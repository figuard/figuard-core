package com.figuard.api.dto.response;

import com.figuard.domain.enums.BudgetStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

// Point-in-time snapshot of budget state captured at authorization decision time.
// Immutable audit record — reflects state when the decision was made, not current state.
@Getter
@Builder
public class BudgetSnapshot {

    private UUID id;

    private BigDecimal totalLimit;
    private BigDecimal amountSpent;
    private BigDecimal amountReserved;
    private BigDecimal availableAmount;
    private BudgetStatus status;
}
