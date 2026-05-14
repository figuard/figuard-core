package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class ApiKeyResponse {

    private UUID id;
    private String keyPrefix;
    private String description;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastUsedAt;

    /**
     * The raw API key — returned ONCE at creation or rotation time only.
     * Null on all subsequent list/get calls. The caller must store this value.
     */
    private String rawKey;
}
