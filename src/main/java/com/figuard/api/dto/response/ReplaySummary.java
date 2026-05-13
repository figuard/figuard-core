package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Aggregate statistics across the full replay window.
 */
@Getter
@Builder
public class ReplaySummary {

    private int totalEvents;
    private int authorizedCount;
    private int deniedCount;
    private int confirmedCount;
    private int failedCount;
    private int voidedCount;
    private int uniqueAgents;
    private BigDecimal peakReservedQuantity;
    private OffsetDateTime peakReservedAt;
}
