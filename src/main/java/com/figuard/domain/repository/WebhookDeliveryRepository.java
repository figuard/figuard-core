package com.figuard.domain.repository;

import com.figuard.domain.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByWebhookConfigId(UUID webhookConfigId);

    List<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(UUID webhookConfigId);

    void deleteByWebhookConfigId(UUID webhookConfigId);
}
