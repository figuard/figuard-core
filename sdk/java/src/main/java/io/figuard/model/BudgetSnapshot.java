package io.figuard.model;

import java.math.BigDecimal;

public record BudgetSnapshot(
        BigDecimal totalLimit,
        BigDecimal amountSpent,
        BigDecimal amountReserved,
        BigDecimal availableAmount,
        String status
) {}
