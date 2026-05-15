package io.figuard.model;

import java.util.List;

public record SpendTreeNode(
        SpendEventResponse event,
        List<SpendTreeNode> children
) {}
