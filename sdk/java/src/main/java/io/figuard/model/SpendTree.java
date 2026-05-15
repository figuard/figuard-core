package io.figuard.model;

import java.util.List;

public record SpendTree(
        String budgetId,
        List<SpendTreeNode> roots,
        int totalEvents
) {}
