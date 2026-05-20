package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.domain.enums.SubscriptionStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class SubscriptionResponse {

    private UUID id;
    private String externalSubscriberId;
    private String name;
    private String description;
    private SubscriptionStatus status;
    private OffsetDateTime subscriptionStartDate;
    private List<EntitlementItemResponse> entitlementItems;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
