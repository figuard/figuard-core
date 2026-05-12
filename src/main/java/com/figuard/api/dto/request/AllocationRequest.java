package com.figuard.api.dto.request;

import com.figuard.domain.enums.EnforcementMode;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AllocationRequest {

    @NotBlank(message = "category is required")
    private String category;

    // Required for CATEGORY_CONSTRAINED and STRICT modes — validated below.
    // Optional (and ignored) for OPEN mode.
    private List<@NotBlank String> allowedCategories;

    // Optional — only evaluated when enforcementMode = STRICT
    private List<String> forbiddenItemTypes;

    // Defaults to CATEGORY_CONSTRAINED if not provided
    private EnforcementMode enforcementMode = EnforcementMode.CATEGORY_CONSTRAINED;

    @AssertTrue(
        message = "allowedCategories is required for CATEGORY_CONSTRAINED and STRICT enforcement modes. " +
                  "It defines which claimedCategory values this allocation will accept. " +
                  "Example: allowedCategories: [\"flight\"]. " +
                  "For OPEN mode, omit allowedCategories — any claimedCategory is accepted."
    )
    private boolean isAllowedCategoriesValidForMode() {
        if (enforcementMode == EnforcementMode.CATEGORY_CONSTRAINED
                || enforcementMode == EnforcementMode.STRICT) {
            return allowedCategories != null && !allowedCategories.isEmpty();
        }
        return true; // OPEN mode: allowedCategories is optional
    }

    @NotNull(message = "limit is required")
    @Positive(message = "limit must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal limit;
}
