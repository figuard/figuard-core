package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.api.dto.request.FundBudgetRequest.FundingOperation;
import com.figuard.domain.enums.BudgetStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class BudgetFundingResponse {

    private UUID budgetId;
    private FundingOperation operation;
    private BigDecimal amount;
    private String reason;

    // State before the operation
    private BigDecimal previousTotalLimit;

    // State after the operation
    private BigDecimal totalLimit;
    private BigDecimal quantitySpent;
    private BigDecimal quantityReserved;
    private BigDecimal availableQuantity;
    private BudgetStatus status;

    private OffsetDateTime updatedAt;
    private String traceId;
}
