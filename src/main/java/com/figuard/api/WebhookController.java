package com.figuard.api;

import com.figuard.api.dto.request.CreateWebhookConfigRequest;
import com.figuard.api.dto.response.WebhookConfigResponse;
import com.figuard.api.dto.response.WebhookDeliveryResponse;
import com.figuard.api.dto.response.WebhookTestResult;
import com.figuard.security.TenantContext;
import com.figuard.service.WebhookConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookConfigService webhookConfigService;

    /**
     * Register a new webhook endpoint for this tenant.
     * Returns HTTP 201 with the created config (secret is never returned).
     */
    @PostMapping
    public ResponseEntity<WebhookConfigResponse> createWebhook(
            @Valid @RequestBody CreateWebhookConfigRequest request) {
        WebhookConfigResponse response = webhookConfigService.createConfig(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all webhook configs for this tenant.
     */
    @GetMapping
    public ResponseEntity<List<WebhookConfigResponse>> listWebhooks() {
        List<WebhookConfigResponse> configs = webhookConfigService.listConfigs(TenantContext.get());
        return ResponseEntity.ok(configs);
    }

    /**
     * Delete a webhook config and all its delivery history.
     * Returns HTTP 204 No Content on success.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable UUID id) {
        webhookConfigService.deleteConfig(id, TenantContext.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * Get delivery history for a webhook config, newest first.
     */
    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<WebhookDeliveryResponse>> getDeliveries(@PathVariable UUID id) {
        List<WebhookDeliveryResponse> deliveries = webhookConfigService.getDeliveries(id, TenantContext.get());
        return ResponseEntity.ok(deliveries);
    }

    /**
     * Fire a test event to the configured URL immediately.
     * Synchronous — returns whether the endpoint responded successfully.
     * Does not create a delivery record (this is a connectivity check, not a real event).
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<WebhookTestResult> testWebhook(@PathVariable UUID id) {
        WebhookTestResult result = webhookConfigService.testConfig(id, TenantContext.get());
        // Return 200 even on delivery failure — the HTTP call itself succeeded.
        // Callers check result.success to know if their endpoint responded with 2xx.
        return ResponseEntity.ok(result);
    }
}
