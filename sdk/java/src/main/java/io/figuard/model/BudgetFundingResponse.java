package io.figuard.model;

import java.math.BigDecimal;

public record BudgetFundingResponse(
        String budgetId,
        String operation,
        BigDecimal previousTotalLimit,
        BigDecimal newTotalLimit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity,
        String status,
        String traceId
) {}
