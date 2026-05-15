package io.figuard.model;

import java.math.BigDecimal;

/** Per-category spend cap on a delegation token. */
public record DelegationTokenCap(
        String id,
        String category,
        BigDecimal totalLimit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity
) {}
