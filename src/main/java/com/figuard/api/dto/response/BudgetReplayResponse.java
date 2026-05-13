package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class BudgetReplayResponse {

    private UUID budgetId;

    private ReplayWindow replayWindow;

    private ReplaySummary summary;

    private ReplayBudgetState initialState;

    private List<ReplayFrame> events;

    private ReplayBudgetState finalState;

    /** Opaque cursor for the next page. Null when this is the last page. */
    private String nextPageToken;

    @Getter
    @Builder
    public static class ReplayWindow {
        private OffsetDateTime from;
        private OffsetDateTime until;
        private long durationSeconds;
    }
}
