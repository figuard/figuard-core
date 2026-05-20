package com.figuard.domain.entity;

import com.figuard.domain.enums.RenewalResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_renewal_logs")
@Getter @Setter @NoArgsConstructor
public class SubscriptionRenewalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entitlement_item_id", nullable = false)
    private EntitlementItem entitlementItem;

    /** Balance consumed in the period that just ended — for billing reconciliation. */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal periodConsumedQuantity;

    /** New limit for the renewed period (may differ if plan was upgraded). */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal newPeriodLimit;

    @Column(nullable = false)
    private OffsetDateTime renewalExecutedAt;

    @Column(nullable = false)
    private OffsetDateTime newPeriodEndsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RenewalResult result;

    /** Linked WebhookDelivery ID — null if webhook dispatch was not attempted. */
    @Column
    private UUID webhookDeliveryId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
