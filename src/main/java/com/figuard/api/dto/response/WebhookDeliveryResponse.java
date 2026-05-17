package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.domain.enums.WebhookDeliveryStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookDeliveryResponse {

    private UUID id;
    private UUID webhookConfigId;  // null for direct-URL deliveries
    private String targetUrl;       // set for direct-URL deliveries, null for config-backed
    private String eventType;
    private WebhookDeliveryStatus status;
    private Integer responseStatus;
    private String responseBody;
    private Map<String, Object> payload;
    private int attemptCount;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime createdAt;
}
