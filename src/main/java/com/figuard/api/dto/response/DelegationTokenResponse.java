package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Builder
public class DelegationTokenResponse {
    private UUID id;
    private UUID parentBudgetId;
    private String label;
    private String status;
    /** Only populated on the create response — null on all subsequent reads. */
    private String sessionToken;
    private String sessionTokenPrefix;
    private List<DelegationTokenAllocationResponse> caps;
    private OffsetDateTime revokedAt;
    private OffsetDateTime createdAt;
}
