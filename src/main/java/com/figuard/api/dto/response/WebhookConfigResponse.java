package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Builder
public class WebhookConfigResponse {

    private UUID id;
    private String url;
    private List<String> events;
    private boolean active;
    private OffsetDateTime createdAt;
    // secret is intentionally omitted — never returned after creation
}
