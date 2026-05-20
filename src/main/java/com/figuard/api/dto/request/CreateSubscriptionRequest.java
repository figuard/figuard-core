package com.figuard.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateSubscriptionRequest {

    @NotBlank(message = "externalSubscriberId is required")
    private String externalSubscriberId;

    @NotBlank(message = "name is required")
    private String name;

    private String description;

    @NotEmpty(message = "at least one entitlement item is required")
    @Valid
    private List<CreateEntitlementItemRequest> entitlementItems;

    private Map<String, Object> metadata;
}
