package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class TimelineResponse {

    private UUID budgetId;
    private int totalEvents;
    private List<TimelineEventItem> timeline;
}
