package io.figuard.model;

import java.math.BigDecimal;

public record AllocationSnapshot(
        String category,
        BigDecimal limit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity,
        String status
) {}
