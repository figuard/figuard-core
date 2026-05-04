package io.figuard.model;

import java.math.BigDecimal;

public record AllocationSnapshot(
        String category,
        BigDecimal limit,
        BigDecimal amountSpent,
        BigDecimal amountReserved,
        BigDecimal availableAmount,
        String status
) {}
