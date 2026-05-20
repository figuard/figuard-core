package com.figuard.domain.enums;

public enum RenewalResult {
    SUCCESS,
    WEBHOOK_FAILED  // renewal executed but delivery failed — retried by WebhookRetryService
}
