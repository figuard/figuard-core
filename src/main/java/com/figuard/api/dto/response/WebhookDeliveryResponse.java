package com.figuard.api.dto.response;

import com.figuard.domain.enums.WebhookDeliveryStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter @Builder
public class WebhookDeliveryResponse {

    private UUID id;
    private UUID webhookConfigId;
    private String eventType;
    private WebhookDeliveryStatus status;
    private Integer responseStatus;
    private String responseBody;
    private int attemptCount;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime createdAt;
}
