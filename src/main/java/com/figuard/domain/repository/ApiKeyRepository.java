package com.figuard.domain.repository;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByTenantOrderByCreatedAtDesc(Tenant tenant);

    Optional<ApiKey> findByIdAndTenant(UUID id, Tenant tenant);
}
