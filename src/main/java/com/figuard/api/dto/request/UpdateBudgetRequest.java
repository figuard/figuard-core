package com.figuard.api.dto.request;

import com.figuard.domain.enums.BudgetStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// All fields optional — PATCH semantics: only provided fields are updated.
@Data
public class UpdateBudgetRequest {

    // Only PAUSED and ACTIVE transitions are allowed via API — EXHAUSTED/EXPIRED are system-set
    private BudgetStatus status;

    @Positive
    @Digits(integer = 15, fraction = 4)
    private BigDecimal totalLimit;

    @Future
    private OffsetDateTime expiresAt;
}
