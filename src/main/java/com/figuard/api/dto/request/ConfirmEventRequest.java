package com.figuard.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfirmEventRequest {

    @NotNull(message = "confirmedAmount is required")
    @DecimalMin(value = "0.01", message = "confirmedAmount must be positive")
    private BigDecimal confirmedAmount;

    // Set when payment processor confirms the charge — blocks void without a refund
    private String externalTransactionId;
}
