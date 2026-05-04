package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Top-level response for GET /budgets/{id}/tree.
 * Contains the list of root nodes (events with no parent), each with their
 * full descendant subtree attached.
 */
@Getter
@Builder
public class SpendTreeResponse {

    private UUID budgetId;

    // Total authorized amount across all root events and their descendants.
    // Only counts AUTHORIZED and CONFIRMED decisions.
    private BigDecimal totalAuthorized;

    // Total confirmed (settled) amount across the full tree.
    private BigDecimal totalConfirmed;

    private int totalEvents;

    // Root nodes — events with no parentEventId.
    // Each carries its full descendant subtree in .children.
    private List<SpendTreeNode> roots;
}
