package com.figuard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.api.dto.response.WebhookTestResult;
import com.figuard.domain.entity.WebhookConfig;
import com.figuard.domain.entity.WebhookDelivery;
import com.figuard.domain.enums.WebhookDeliveryStatus;
import com.figuard.domain.enums.WebhookEventType;
import com.figuard.domain.repository.WebhookConfigRepository;
import com.figuard.domain.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fires outbound webhooks asynchronously after budget/spend events.
 *
 * Each dispatch call:
 *   1. Finds all active webhook configs subscribed to this event type
 *   2. Builds the signed HTTP request (HMAC-SHA256 over the JSON body)
 *   3. POSTs to the configured URL
 *   4. Retries up to 3 times with exponential backoff (1s → 2s → 4s)
 *   5. Records every delivery attempt in webhook_deliveries
 *
 * Runs on the "webhookExecutor" thread pool so the authorize path is never blocked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcher {

    // Retry delays in ms: initial=0, retry1=1s, retry2=2s, retry3=4s
    private static final int[] RETRY_DELAYS_MS = {0, 1_000, 2_000, 4_000};

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final WebhookSecretEncryptor secretEncryptor;

    // -------------------------------------------------------------------------
    // Public API — called after authorize/void/sweep events
    // -------------------------------------------------------------------------

    @Async("webhookExecutor")
    public void dispatch(UUID tenantId, WebhookEventType eventType, Map<String, Object> payload) {
        List<WebhookConfig> configs = webhookConfigRepository.findByTenantIdAndIsActiveTrue(tenantId);

        for (WebhookConfig config : configs) {
            if (!subscribesTo(config, eventType)) continue;
            deliver(config, eventType, payload);
        }
    }

    /**
     * Dispatch to a specific URL (e.g. anomalyAlertWebhookUrl) rather than all
     * tenant configs subscribed to the event. Writes a WebhookDelivery record
     * (webhookConfig=null, targetUrl=url) so the delivery is visible in the dashboard
     * and retryable if it fails.
     */
    @Async("webhookExecutor")
    public void dispatchToUrl(String url, UUID tenantId,
                               WebhookEventType eventType, Map<String, Object> payload) {
        deliverToUrl(url, tenantId, eventType, payload);
    }

    /**
     * Retry a previously FAILED direct-URL delivery. Called synchronously from
     * WebhookConfigService — the caller handles async threading.
     */
    public void retryDirectDelivery(WebhookDelivery existing) {
        deliverToUrl(existing.getTargetUrl(), null,
            WebhookEventType.valueOf(existing.getEventType()), existing.getPayload());
    }

    @Transactional
    protected void deliverToUrl(String url, UUID tenantId,
                                 WebhookEventType eventType, Map<String, Object> payload) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTargetUrl(url);
        delivery.setEventType(eventType.name());
        delivery.setPayload(payload);
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        deliveryRepository.save(delivery);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize direct-URL payload for url={}: {}", url, e.getMessage());
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            return;
        }

        // Sign with a synthetic secret derived from tenantId (or URL host) so the receiver can verify
        String signingKey = tenantId != null ? tenantId.toString() : url;
        String signature;
        try {
            signature = hmacSha256(payloadJson, signingKey);
        } catch (Exception e) {
            log.error("Failed to sign direct-URL payload for url={}: {}", url, e.getMessage());
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            return;
        }

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        for (int attempt = 0; attempt < RETRY_DELAYS_MS.length; attempt++) {
            if (RETRY_DELAYS_MS[attempt] > 0) {
                sleep(RETRY_DELAYS_MS[attempt]);
            }
            delivery.setAttemptCount(attempt + 1);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", "sha256=" + signature)
                    .header("X-Webhook-Event", eventType.name())
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .timeout(Duration.ofSeconds(10))
                    .build();
                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
                delivery.setResponseStatus(response.statusCode());
                delivery.setResponseBody(truncate(response.body(), 2000));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
                    delivery.setDeliveredAt(OffsetDateTime.now());
                    deliveryRepository.save(delivery);
                    log.info("Direct-URL webhook delivered: url={} event={} attempt={}", url, eventType, attempt + 1);
                    return;
                }
                log.warn("Direct-URL webhook non-2xx: url={} event={} status={} attempt={}",
                    url, eventType, response.statusCode(), attempt + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Direct-URL webhook attempt {} failed for url={} event={}: {}",
                    attempt + 1, url, eventType, e.getMessage());
            }
        }

        delivery.setStatus(WebhookDeliveryStatus.FAILED);
        deliveryRepository.save(delivery);
        log.error("Direct-URL webhook failed after {} attempts: url={} event={}", RETRY_DELAYS_MS.length, url, eventType);
    }

    // -------------------------------------------------------------------------
    // Delivery with retry
    // -------------------------------------------------------------------------

    @Transactional
    public void deliver(WebhookConfig config, WebhookEventType eventType,
                           Map<String, Object> payload) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookConfig(config);
        delivery.setEventType(eventType.name());
        delivery.setPayload(payload);
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        deliveryRepository.save(delivery);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload for config {}: {}", config.getId(), e.getMessage());
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            return;
        }

        String signature;
        try {
            signature = hmacSha256(payloadJson, secretEncryptor.decrypt(config.getSecret()));
        } catch (Exception e) {
            log.error("Failed to sign webhook payload for config {}: {}", config.getId(), e.getMessage());
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            return;
        }

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        for (int attempt = 0; attempt < RETRY_DELAYS_MS.length; attempt++) {
            if (RETRY_DELAYS_MS[attempt] > 0) {
                sleep(RETRY_DELAYS_MS[attempt]);
            }

            delivery.setAttemptCount(attempt + 1);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", "sha256=" + signature)
                    .header("X-Webhook-Event", eventType.name())
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                delivery.setResponseStatus(response.statusCode());
                delivery.setResponseBody(truncate(response.body(), 2000));

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
                    delivery.setDeliveredAt(OffsetDateTime.now());
                    deliveryRepository.save(delivery);
                    log.info("Webhook delivered: configId={} event={} attempt={}",
                        config.getId(), eventType, attempt + 1);
                    return;
                }

                log.warn("Webhook non-2xx: configId={} event={} status={} attempt={}",
                    config.getId(), eventType, response.statusCode(), attempt + 1);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Webhook attempt {} failed for configId={} event={}: {}",
                    attempt + 1, config.getId(), eventType, e.getMessage());
            }
        }

        // All attempts exhausted
        delivery.setStatus(WebhookDeliveryStatus.FAILED);
        deliveryRepository.save(delivery);
        log.error("Webhook delivery failed after {} attempts: configId={} event={}",
            RETRY_DELAYS_MS.length, config.getId(), eventType);
    }

    // -------------------------------------------------------------------------
    // Test delivery — synchronous, single attempt, no DB record, returns result
    // -------------------------------------------------------------------------

    public WebhookTestResult testDeliver(WebhookConfig config) {
        Map<String, Object> payload = Map.of(
            "eventType", WebhookEventType.WEBHOOK_TEST.name(),
            "timestamp", OffsetDateTime.now().toString(),
            "tenantId", config.getTenant().getId().toString(),
            "message", "This is a test webhook from FiGuard. If you received this, your endpoint is configured correctly."
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return WebhookTestResult.builder()
                .success(false)
                .errorMessage("Failed to serialize test payload: " + e.getMessage())
                .durationMs(0)
                .build();
        }

        String signature;
        try {
            signature = hmacSha256(payloadJson, secretEncryptor.decrypt(config.getSecret()));
        } catch (Exception e) {
            return WebhookTestResult.builder()
                .success(false)
                .errorMessage("Failed to sign test payload: " + e.getMessage())
                .durationMs(0)
                .build();
        }

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Signature", "sha256=" + signature)
                .header("X-Webhook-Event", WebhookEventType.WEBHOOK_TEST.name())
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            long durationMs = System.currentTimeMillis() - start;
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return WebhookTestResult.builder()
                .success(success)
                .responseStatus(response.statusCode())
                .responseBody(truncate(response.body(), 500))
                .durationMs(durationMs)
                .build();

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            return WebhookTestResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .durationMs(durationMs)
                .build();
        }
    }

    // -------------------------------------------------------------------------
    // Retry sweep — single attempt on an existing FAILED delivery
    // -------------------------------------------------------------------------

    /**
     * Attempts one HTTP delivery against an already-persisted WebhookDelivery.
     * Called by WebhookRetryService — does NOT create a new delivery record.
     * Updates attemptCount, responseStatus, responseBody, status, deliveredAt on the
     * existing row and saves. The retry service sets nextRetryAt after this returns.
     *
     * @return true if the delivery succeeded (2xx response)
     */
    @Transactional
    public boolean attemptSingleRetry(WebhookDelivery delivery) {
        String url = delivery.getWebhookConfig() != null
            ? delivery.getWebhookConfig().getUrl()
            : delivery.getTargetUrl();

        String signingKey = delivery.getWebhookConfig() != null
            ? secretEncryptor.decrypt(delivery.getWebhookConfig().getSecret())
            : url;  // direct-URL deliveries use the URL as a synthetic signing key (not encrypted)

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(delivery.getPayload());
        } catch (Exception e) {
            log.error("Retry: failed to serialize payload for delivery {}: {}", delivery.getId(), e.getMessage());
            return false;
        }

        String signature;
        try {
            signature = hmacSha256(payloadJson, signingKey);
        } catch (Exception e) {
            log.error("Retry: failed to sign payload for delivery {}: {}", delivery.getId(), e.getMessage());
            return false;
        }

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Signature", "sha256=" + signature)
                .header("X-Webhook-Event", delivery.getEventType())
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            delivery.setResponseStatus(response.statusCode());
            delivery.setResponseBody(truncate(response.body(), 2000));

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (success) {
                delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
                delivery.setDeliveredAt(OffsetDateTime.now());
                log.info("Retry succeeded: deliveryId={} event={} attempt={}",
                    delivery.getId(), delivery.getEventType(), delivery.getAttemptCount());
            } else {
                log.warn("Retry non-2xx: deliveryId={} event={} status={} attempt={}",
                    delivery.getId(), delivery.getEventType(),
                    response.statusCode(), delivery.getAttemptCount());
            }
            deliveryRepository.save(delivery);
            return success;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deliveryRepository.save(delivery);
            return false;
        } catch (Exception e) {
            log.warn("Retry attempt failed: deliveryId={} event={} attempt={}: {}",
                delivery.getId(), delivery.getEventType(), delivery.getAttemptCount(), e.getMessage());
            deliveryRepository.save(delivery);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean subscribesTo(WebhookConfig config, WebhookEventType eventType) {
        return config.getEvents() != null
            && Arrays.asList(config.getEvents()).contains(eventType.name());
    }

    public static String hmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sig);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
