package com.figuard.api.dto.request;

import com.figuard.domain.enums.OveragePolicy;
import com.figuard.domain.enums.RenewalPeriod;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateEntitlementItemRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "limitUnit is required")
    private String limitUnit;

    @NotNull(message = "limitQuantity is required")
    @Positive(message = "limitQuantity must be positive")
    @Digits(integer = 14, fraction = 6)
    private BigDecimal limitQuantity;

    @NotNull(message = "renewalPeriod is required")
    private RenewalPeriod renewalPeriod;

    /** Day of month the renewal anchors to (1–28). Defaults to subscription start day. */
    @Min(1) @Max(28)
    private Integer renewalAnchorDay;

    private OveragePolicy overagePolicy = OveragePolicy.BLOCK;

    @Min(1) @Max(99)
    private Integer warnAtPercentage = 80;
}
