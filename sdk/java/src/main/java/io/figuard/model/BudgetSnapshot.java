package io.figuard.model;

import java.math.BigDecimal;

public record BudgetSnapshot(
        BigDecimal totalLimit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity,
        String status
) {}
