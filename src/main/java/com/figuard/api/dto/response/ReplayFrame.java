package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * One step in a full budget replay: the event that occurred and the budget state after it applied.
 */
@Getter
@Builder
public class ReplayFrame {

    private int eventIndex;
    private ReplayEventDetail event;
    private ReplayBudgetState stateAfter;   // null when includeStateSnapshots=false
}
