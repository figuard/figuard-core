package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VoidTreeRequest {

    @NotBlank(message = "reason is required")
    private String reason;
}
