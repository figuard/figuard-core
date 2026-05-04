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

    @NotEmpty(message = "allowedCategories must not be empty")
    private List<@NotBlank String> allowedCategories;

    // Optional — only evaluated when enforcementMode = STRICT
    private List<String> forbiddenItemTypes;

    // Defaults to CATEGORY_CONSTRAINED if not provided
    private EnforcementMode enforcementMode = EnforcementMode.CATEGORY_CONSTRAINED;

    @NotNull(message = "limit is required")
    @Positive(message = "limit must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal limit;
}
