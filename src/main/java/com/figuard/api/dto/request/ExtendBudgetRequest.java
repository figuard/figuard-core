package com.figuard.api.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ExtendBudgetRequest {

    /**
     * New expiry time. Must be in the future, no more than 24h from now,
     * and later than the budget's current expiresAt.
     */
    @NotNull(message = "expiresAt is required")
    @Future(message = "expiresAt must be in the future")
    private OffsetDateTime expiresAt;
}
