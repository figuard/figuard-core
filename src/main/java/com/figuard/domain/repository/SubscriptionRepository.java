package com.figuard.domain.repository;

import com.figuard.domain.entity.Subscription;
import com.figuard.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByTenantIdAndExternalSubscriberId(UUID tenantId, String externalSubscriberId);

    List<Subscription> findByTenantIdAndStatus(UUID tenantId, SubscriptionStatus status);

    List<Subscription> findByTenantId(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.id = :id")
    Optional<Subscription> findByIdWithLock(@Param("id") UUID id);
}
