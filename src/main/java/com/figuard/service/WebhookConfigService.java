package com.figuard.service;

import com.figuard.api.dto.request.CreateWebhookConfigRequest;
import com.figuard.api.dto.response.WebhookConfigResponse;
import com.figuard.api.dto.response.WebhookDeliveryResponse;
import com.figuard.api.dto.response.WebhookTestResult;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.entity.WebhookConfig;
import com.figuard.domain.entity.WebhookDelivery;
import com.figuard.domain.enums.WebhookDeliveryStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.WebhookConfigRepository;
import com.figuard.domain.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WebhookConfigService {

    private static final Set<String> VALID_EVENT_TYPES = Arrays.stream(WebhookEventType.values())
        .filter(e -> e != WebhookEventType.WEBHOOK_TEST)  // not subscribable — internal only
        .map(Enum::name)
        .collect(Collectors.toSet());

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookSecretEncryptor secretEncryptor;

    @Transactional
    public WebhookConfigResponse createConfig(CreateWebhookConfigRequest request, Tenant tenant) {
        validateEventTypes(request.getEvents());

        WebhookConfig config = new WebhookConfig();
        config.setTenant(tenant);
        config.setUrl(request.getUrl());
        config.setSecret(secretEncryptor.encrypt(request.getSecret()));
        config.setActive(true);
        config.setEvents(request.getEvents().toArray(new String[0]));

        WebhookConfig saved = webhookConfigRepository.save(config);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WebhookConfigResponse> listConfigs(Tenant tenant) {
        return webhookConfigRepository.findByTenantId(tenant.getId())
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public void deleteConfig(UUID id, Tenant tenant) {
        WebhookConfig config = findOwnedConfig(id, tenant);
        // Delete deliveries first (FK constraint)
        webhookDeliveryRepository.deleteByWebhookConfigId(config.getId());
        webhookConfigRepository.delete(config);
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> getDeliveries(UUID configId, Tenant tenant) {
        findOwnedConfig(configId, tenant);  // tenant isolation check
        return webhookDeliveryRepository.findByWebhookConfigIdOrderByCreatedAtDesc(configId)
            .stream()
            .map(this::toDeliveryResponse)
            .toList();
    }

    public WebhookTestResult testConfig(UUID id, Tenant tenant) {
        WebhookConfig config = findOwnedConfig(id, tenant);
        return webhookDispatcher.testDeliver(config);
    }

    // -------------------------------------------------------------------------
    // Tenant-scoped delivery list (across all configs) — for dashboard tab
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> getAllDeliveries(
            Tenant tenant,
            WebhookDeliveryStatus status,
            String eventType,
            java.time.OffsetDateTime since) {
        return webhookDeliveryRepository
            .findFiltered(tenant.getId(), status, eventType, since)
            .stream()
            .map(this::toDeliveryResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getFailedCount(Tenant tenant) {
        return Map.of("failedCount", webhookDeliveryRepository.countFailedByTenantId(tenant.getId()));
    }

    @Async("webhookExecutor")
    @Transactional
    public void retryDelivery(UUID deliveryId, Tenant tenant) {
        WebhookDelivery delivery = webhookDeliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found: " + deliveryId));

        // Tenant isolation: config-backed delivery must belong to this tenant
        if (delivery.getWebhookConfig() != null
                && !delivery.getWebhookConfig().getTenant().getId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found: " + deliveryId);
        }

        if (delivery.getStatus() != WebhookDeliveryStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only FAILED deliveries can be retried (current status: " + delivery.getStatus() + ")");
        }

        if (delivery.getWebhookConfig() != null) {
            webhookDispatcher.deliver(delivery.getWebhookConfig(),
                WebhookEventType.valueOf(delivery.getEventType()), delivery.getPayload());
        } else {
            webhookDispatcher.retryDirectDelivery(delivery);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WebhookConfig findOwnedConfig(UUID id, Tenant tenant) {
        return webhookConfigRepository.findByIdAndTenantId(id, tenant.getId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Webhook config not found: " + id));
    }

    private void validateEventTypes(List<String> events) {
        List<String> invalid = events.stream()
            .filter(e -> !VALID_EVENT_TYPES.contains(e))
            .toList();
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid event types: " + invalid + ". Valid types: " + VALID_EVENT_TYPES);
        }
    }

    private WebhookConfigResponse toResponse(WebhookConfig config) {
        return WebhookConfigResponse.builder()
            .id(config.getId())
            .url(config.getUrl())
            .events(List.of(config.getEvents()))
            .active(config.isActive())
            .createdAt(config.getCreatedAt())
            .build();
    }

    private WebhookDeliveryResponse toDeliveryResponse(WebhookDelivery d) {
        return WebhookDeliveryResponse.builder()
            .id(d.getId())
            .webhookConfigId(d.getWebhookConfig() != null ? d.getWebhookConfig().getId() : null)
            .targetUrl(d.getTargetUrl())
            .eventType(d.getEventType())
            .status(d.getStatus())
            .responseStatus(d.getResponseStatus())
            .responseBody(d.getResponseBody())
            .payload(d.getPayload())
            .attemptCount(d.getAttemptCount())
            .deliveredAt(d.getDeliveredAt())
            .createdAt(d.getCreatedAt())
            .build();
    }
}
