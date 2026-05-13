package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class PointInTimeStateResponse {

    private UUID budgetId;
    private OffsetDateTime projectedAt;
    private int eventsApplied;
    private ReplayBudgetState state;
}
