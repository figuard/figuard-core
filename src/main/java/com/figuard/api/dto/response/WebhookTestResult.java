package com.figuard.api.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter @Builder
public class WebhookTestResult {

    private boolean success;
    private Integer responseStatus;
    private String responseBody;
    private long durationMs;
    private String errorMessage;     // populated when success=false due to network error
}
