package com.figuard.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class HypotheticalAllocation {

    @NotBlank
    private String category;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal limit;

    private String enforcementMode = "CATEGORY_CONSTRAINED";

    private List<String> allowedCategories;
    private List<String> forbiddenItemTypes;
}
