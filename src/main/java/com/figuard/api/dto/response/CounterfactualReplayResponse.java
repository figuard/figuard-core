package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class CounterfactualReplayResponse {

    private UUID budgetId;

    /** Describes whether the policy came from an inline hypothetical_policy or a manifest_version. */
    private PolicySource policySource;

    private PolicySummary actualPolicySummary;
    private PolicySummary hypotheticalPolicySummary;

    /** Events whose outcome would differ under the hypothetical policy. */
    private List<CounterfactualDelta> deltaEvents;

    @Getter
    @Builder
    public static class PolicySource {
        /** "inline" or "manifest_version" */
        private String type;
        /** Populated when type = "manifest_version". */
        private String manifestVersion;
    }

    @Getter
    @Builder
    public static class PolicySummary {
        private int authorizedCount;
        private int deniedCount;
        private BigDecimal totalQuantitySpent;
        /** Only present on the hypothetical summary. */
        private Integer additionalDenials;
    }
}
