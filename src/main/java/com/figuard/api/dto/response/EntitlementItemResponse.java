package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.OveragePolicy;
import com.figuard.domain.enums.RenewalPeriod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class EntitlementItemResponse {

    private UUID id;
    private UUID subscriptionId;
    private String name;
    private String limitUnit;
    private BigDecimal limitQuantity;
    private BigDecimal currentPeriodConsumed;
    private BigDecimal remaining;
    private int consumedPercentage;
    private int warnAtPercentage;
    private RenewalPeriod renewalPeriod;
    private OffsetDateTime nextRenewalAt;
    private OveragePolicy overagePolicy;
    private EntitlementState state;
    private OffsetDateTime lastStateTransitionAt;
    private OffsetDateTime createdAt;
}
