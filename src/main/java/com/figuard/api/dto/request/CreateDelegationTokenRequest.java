package com.figuard.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter @Setter
public class CreateDelegationTokenRequest {

    /**
     * Human-readable label for this delegation token.
     * Typically the sub-agent identifier or order ID: "refund-agent-order-123".
     */
    @NotBlank
    private String label;

    /**
     * Per-category spend caps. Only categories listed here are cap-enforced at the
     * delegation level. Categories not listed pass through directly to the fleet allocation.
     * At least one cap is required.
     */
    @NotEmpty
    @Valid
    private List<DelegationCapRequest> caps;

    @Getter @Setter
    public static class DelegationCapRequest {

        /** Must match the category label used in the parent budget's allocations (case-insensitive). */
        @NotBlank
        private String category;

        /** Maximum quantity this delegation token may spend/reserve for this category. */
        @Positive
        private BigDecimal limit;
    }
}
