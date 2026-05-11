package com.figuard.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfirmEventRequest {

    @NotNull(message = "confirmedQuantity is required")
    @DecimalMin(value = "0.00", message = "confirmedQuantity must be zero or positive")
    private BigDecimal confirmedQuantity;

    // Set when payment processor confirms the charge — blocks void without a refund
    private String externalTransactionId;
}
