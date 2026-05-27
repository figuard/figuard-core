package com.figuard.domain.repository;

import com.figuard.domain.entity.WebhookDelivery;
import com.figuard.domain.enums.WebhookDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByWebhookConfigId(UUID webhookConfigId);

    List<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(UUID webhookConfigId);

    void deleteByWebhookConfigId(UUID webhookConfigId);

    // Tenant-scoped delivery list across all webhook configs (for the dashboard tab).
    // Joins through webhook_configs to enforce tenant isolation.
    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.webhookConfig.tenant.id = :tenantId
        ORDER BY d.createdAt DESC
        """)
    List<WebhookDelivery> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);

    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.webhookConfig.tenant.id = :tenantId
          AND d.status = :status
        ORDER BY d.createdAt DESC
        """)
    List<WebhookDelivery> findByTenantIdAndStatusOrderByCreatedAtDesc(
        @Param("tenantId") UUID tenantId,
        @Param("status") WebhookDeliveryStatus status);

    // Filtered delivery list — all params optional; null = no constraint.
    //
    // COALESCE trick for all nullable params: Hibernate 6 + PostgreSQL cannot determine
    // the SQL type of null parameters that appear only in IS NULL checks (no adjacent typed
    // context). This causes "could not determine data type of parameter $N" at runtime.
    //   - COALESCE(:status, d.status) = d.status   → always true when status is null
    //   - COALESCE(:eventType, d.eventType) = d.eventType → always true when eventType is null
    //   - d.createdAt >= COALESCE(:since, d.createdAt)  → always true when since is null
    // In each case PostgreSQL infers the parameter type from the non-null column operand.
    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.webhookConfig.tenant.id = :tenantId
          AND COALESCE(:status, d.status) = d.status
          AND COALESCE(:eventType, d.eventType) = d.eventType
          AND d.createdAt >= COALESCE(:since, d.createdAt)
        ORDER BY d.createdAt DESC
        """)
    List<WebhookDelivery> findFiltered(
        @Param("tenantId")  UUID tenantId,
        @Param("status")    WebhookDeliveryStatus status,
        @Param("eventType") String eventType,
        @Param("since")     OffsetDateTime since);

    // Retry sweep — FAILED deliveries due for re-attempt.
    // nextRetryAt IS NULL catches deliveries that failed before the retry service existed.
    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.status = com.figuard.domain.enums.WebhookDeliveryStatus.FAILED
          AND d.attemptCount < 10
          AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :now)
        ORDER BY d.nextRetryAt ASC NULLS FIRST
        """)
    List<WebhookDelivery> findRetriableDeliveries(@Param("now") OffsetDateTime now);

    // Count of FAILED deliveries for the nav badge.
    @Query("""
        SELECT COUNT(d) FROM WebhookDelivery d
        WHERE d.webhookConfig.tenant.id = :tenantId
          AND d.status = com.figuard.domain.enums.WebhookDeliveryStatus.FAILED
        """)
    long countFailedByTenantId(@Param("tenantId") UUID tenantId);
}
