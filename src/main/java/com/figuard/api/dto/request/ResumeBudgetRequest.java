package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResumeBudgetRequest {

    /**
     * Required human-readable explanation of why the budget is being resumed.
     * Written to logs and included in the BUDGET_RESUMED webhook payload.
     */
    @NotBlank(message = "overrideReason is required")
    private String overrideReason;

    /**
     * Optional identifier for the operator or system performing the override.
     * Included in the BUDGET_RESUMED webhook payload for audit trail.
     */
    private String overrideBy;
}
