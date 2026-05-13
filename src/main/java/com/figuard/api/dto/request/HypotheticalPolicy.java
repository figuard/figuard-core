package com.figuard.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class HypotheticalPolicy {

    @DecimalMin("0.01")
    private BigDecimal totalLimit;

    @Valid
    private List<HypotheticalAllocation> allocations;

    private BigDecimal maxTransactionQuantity;

    private boolean anomalyDetectionEnabled;
}
