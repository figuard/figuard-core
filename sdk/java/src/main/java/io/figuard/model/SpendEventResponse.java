package io.figuard.model;

import java.math.BigDecimal;

public record SpendEventResponse(
        String id,
        String decision,
        BigDecimal requestedAmount,
        String currency,
        String createdAt,
        String agentId,
        String agentType,
        String actionType,
        String description,
        BigDecimal confirmedAmount,
        String entityId,
        String claimedCategory,
        String claimedItemType,
        String idempotencyKey,
        String denialReason,
        String failureReason,
        String parentEventId
) {}
