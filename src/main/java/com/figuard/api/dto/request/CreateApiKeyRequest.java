package com.figuard.api.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApiKeyRequest {

    /** Optional human-readable label for this key (e.g. "CI pipeline", "production agent"). */
    @Size(max = 255)
    private String description;
}
