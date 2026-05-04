package com.figuard.domain.repository;

import com.figuard.domain.entity.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    List<WebhookConfig> findByTenantIdAndIsActiveTrue(UUID tenantId);

    List<WebhookConfig> findByTenantId(UUID tenantId);

    Optional<WebhookConfig> findByIdAndTenantId(UUID id, UUID tenantId);
}
