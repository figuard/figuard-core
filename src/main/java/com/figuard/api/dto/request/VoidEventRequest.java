package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VoidEventRequest {

    @NotBlank(message = "reason is required")
    private String reason;

    // When true, any child events (same parentEventId chain) are also voided
    private boolean voidChildEvents = false;
}
