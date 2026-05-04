package com.figuard.domain.enums;

public enum WebhookDeliveryStatus {
    PENDING,     // queued, no attempt yet or last attempt in progress
    DELIVERED,   // at least one 2xx response received
    FAILED       // all retry attempts exhausted without a 2xx response
}
