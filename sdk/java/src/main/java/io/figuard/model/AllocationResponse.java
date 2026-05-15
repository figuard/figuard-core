package io.figuard.model;

import java.math.BigDecimal;
import java.util.List;

public record AllocationResponse(
        String id,
        String category,
        List<String> allowedCategories,
        BigDecimal limit,
        BigDecimal quantitySpent,
        BigDecimal quantityReserved,
        BigDecimal availableQuantity,
        String status,
        String enforcementMode,
        List<String> forbiddenItemTypes
) {}
