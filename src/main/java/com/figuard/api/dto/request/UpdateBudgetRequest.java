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

    // Rolling-window velocity controls. Null = leave unchanged; pass 0 is rejected by @Positive.
    @Positive(message = "velocityMaxPerMinute must be positive")
    private Integer velocityMaxPerMinute;

    @Positive(message = "velocityMaxAmountPerHour must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal velocityMaxAmountPerHour;

    @Positive(message = "velocityMaxPerDay must be positive")
    private Integer velocityMaxPerDay;
}
