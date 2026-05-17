package com.figuard.domain.entity;

import com.figuard.domain.enums.WebhookDeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Getter @Setter @NoArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_config_id", nullable = true)
    private WebhookConfig webhookConfig;

    // Populated for direct-URL dispatches (e.g. anomalyAlertWebhookUrl) where
    // no WebhookConfig row exists. Exactly one of webhookConfig or targetUrl is set.
    @Column(length = 2048)
    private String targetUrl;

    @Column(nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    private Integer responseStatus;

    @Column(columnDefinition = "text")
    private String responseBody;

    @Column(nullable = false)
    private int attemptCount = 0;

    private OffsetDateTime deliveredAt;

    private OffsetDateTime nextRetryAt;

    /**
     * Set to true once RENEWAL_TOKEN_DELIVERY_FAILED has been dispatched for this delivery.
     * Only relevant when eventType = "entitlement.renewed". Prevents re-firing on each sweep pass.
     */
    @Column(nullable = false)
    private boolean renewalAlertSent = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
