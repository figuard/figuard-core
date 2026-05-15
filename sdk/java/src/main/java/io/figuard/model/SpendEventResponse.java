package io.figuard.model;

import java.math.BigDecimal;
import java.util.Map;

public record SpendEventResponse(
        String id,
        String decision,
        BigDecimal requestedQuantity,
        String currency,
        String createdAt,
        String agentId,
        String agentType,
        String actionType,
        String description,
        BigDecimal confirmedQuantity,
        String entityId,
        String claimedCategory,
        String claimedItemType,
        String intentContext,
        String idempotencyKey,
        String denialReason,
        String failureReason,
        String parentEventId,
        String traceId,
        Map<String, Object> metadata
) {}
