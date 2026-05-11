package com.figuard.api.dto.response;

import com.figuard.domain.enums.AllocationStatus;
import com.figuard.domain.enums.EnforcementMode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class AllocationResponse {

    private UUID id;
    private String category;
    private List<String> allowedCategories;
    private List<String> forbiddenItemTypes;    // null when not set
    private EnforcementMode enforcementMode;
    private BigDecimal limit;
    private BigDecimal quantitySpent;
    private BigDecimal quantityReserved;
    private BigDecimal availableQuantity;
    private AllocationStatus status;
}
