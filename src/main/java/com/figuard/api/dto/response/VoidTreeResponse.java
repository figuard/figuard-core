package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned by POST /events/{id}/void-tree.
 *
 * Describes the entire subtree that was atomically voided — the root event,
 * every authorized descendant, and the total budget capacity released.
 */
@Data
@Builder
public class VoidTreeResponse {

    /** The root event that was the void target. */
    private UUID rootEventId;

    /** Total number of events voided, including the root. */
    private int voidedCount;

    /** Sum of requestedQuantity across all voided events. This is the amount released back to the budget. */
    private BigDecimal totalQuantityReleased;

    /** Currency of the released amount (e.g. "USD"), or null for unit-based budgets. */
    private String currency;

    /** IDs of every event that was voided, root first then descendants in BFS order. */
    private List<UUID> voidedEventIds;

    /** The reason supplied by the caller. */
    private String reason;
}
