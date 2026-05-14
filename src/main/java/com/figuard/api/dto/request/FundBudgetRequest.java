package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class FundBudgetRequest {

    /**
     * CREDIT  — add amount to totalLimit (and available).
     * DEBIT   — subtract amount from totalLimit; fails if result would go below quantitySpent.
     * RESET   — set totalLimit to exactly amount; fails if amount < quantitySpent.
     * RESET_SPENT — start a new billing period: zero quantitySpent, keep quantityReserved.
     *              amount is the new totalLimit for the fresh period.
     */
    @NotNull
    private FundingOperation operation;

    /**
     * Amount to apply. Required for CREDIT, DEBIT, RESET, and RESET_SPENT.
     * Must be positive for all operations.
     */
    @NotNull
    @Positive
    private BigDecimal amount;

    /** Optional note recorded in the response for audit purposes. */
    private String reason;

    public enum FundingOperation {
        CREDIT, DEBIT, RESET, RESET_SPENT
    }
}
