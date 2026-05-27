package com.figuard.api;

import com.figuard.api.dto.request.CreateWebhookConfigRequest;
import com.figuard.api.dto.response.WebhookConfigResponse;
import com.figuard.api.dto.response.WebhookDeliveryResponse;
import com.figuard.api.dto.response.WebhookTestResult;
import com.figuard.domain.enums.WebhookDeliveryStatus;
import com.figuard.security.TenantContext;
import com.figuard.service.WebhookConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Webhooks", description = "Register endpoints to receive real-time FiGuard events (budget exhausted, anomaly detected, token rotated, etc.). Events are delivered via HTTP POST with HMAC-SHA256 signatures.")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookConfigService webhookConfigService;

    @Operation(
        summary = "Register a webhook",
        description = "Register a new webhook endpoint for this tenant. FiGuard will POST signed event payloads to the URL you provide. The signing secret is generated server-side and never returned after creation — verify deliveries by checking the `X-FiGuard-Signature` header."
    )
    @ApiResponse(responseCode = "201", description = "Webhook registered")
    @PostMapping
    public ResponseEntity<WebhookConfigResponse> createWebhook(
            @Valid @RequestBody CreateWebhookConfigRequest request) {
        WebhookConfigResponse response = webhookConfigService.createConfig(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List webhooks", description = "List all webhook configurations for this tenant.")
    @GetMapping
    public ResponseEntity<List<WebhookConfigResponse>> listWebhooks() {
        List<WebhookConfigResponse> configs = webhookConfigService.listConfigs(TenantContext.get());
        return ResponseEntity.ok(configs);
    }

    @Operation(summary = "Delete a webhook", description = "Delete a webhook config and all its delivery history. Returns 204 No Content.")
    @ApiResponse(responseCode = "204", description = "Webhook deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable UUID id) {
        webhookConfigService.deleteConfig(id, TenantContext.get());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get delivery history", description = "Delivery history for a specific webhook config, newest first.")
    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<WebhookDeliveryResponse>> getDeliveries(@PathVariable UUID id) {
        List<WebhookDeliveryResponse> deliveries = webhookConfigService.getDeliveries(id, TenantContext.get());
        return ResponseEntity.ok(deliveries);
    }

    @Operation(
        summary = "Test a webhook",
        description = "Fire a test event to the configured URL synchronously. Returns whether the endpoint responded with 2xx. Does not create a delivery record — this is a connectivity check only."
    )
    @PostMapping("/{id}/test")
    public ResponseEntity<WebhookTestResult> testWebhook(@PathVariable UUID id) {
        WebhookTestResult result = webhookConfigService.testConfig(id, TenantContext.get());
        // Return 200 even on delivery failure — the HTTP call itself succeeded.
        // Callers check result.success to know if their endpoint responded with 2xx.
        return ResponseEntity.ok(result);
    }

    @Operation(
        summary = "List all deliveries",
        description = "All deliveries for this tenant across all webhook configs, newest first. All filter params are optional and combinable."
    )
    @GetMapping("/deliveries")
    public ResponseEntity<List<WebhookDeliveryResponse>> getAllDeliveries(
            @Parameter(description = "Filter by delivery status") @RequestParam(required = false) WebhookDeliveryStatus status,
            @Parameter(description = "Filter by event type (e.g. SPEND_CONFIRMED)") @RequestParam(required = false) String eventType,
            @Parameter(description = "Only include deliveries created at or after this ISO-8601 timestamp") @RequestParam(required = false) java.time.OffsetDateTime since) {
        return ResponseEntity.ok(
            webhookConfigService.getAllDeliveries(TenantContext.get(), status, eventType, since));
    }

    @Operation(summary = "Count failed deliveries", description = "Returns the count of FAILED deliveries for this tenant. Used by the dashboard to show a warning badge.")
    @GetMapping("/deliveries/failed-count")
    public ResponseEntity<Map<String, Object>> getFailedCount() {
        return ResponseEntity.ok(webhookConfigService.getFailedCount(TenantContext.get()));
    }

    @Operation(
        summary = "Retry a failed delivery",
        description = "Re-attempt a FAILED delivery. Returns 202 Accepted immediately — the retry fires asynchronously."
    )
    @ApiResponse(responseCode = "202", description = "Retry queued")
    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<Void> retryDelivery(@PathVariable UUID deliveryId) {
        webhookConfigService.retryDelivery(deliveryId, TenantContext.get());
        return ResponseEntity.accepted().build();
    }
}
