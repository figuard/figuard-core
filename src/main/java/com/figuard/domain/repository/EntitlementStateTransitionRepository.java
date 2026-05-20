package com.figuard.domain.repository;

import com.figuard.domain.entity.EntitlementStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntitlementStateTransitionRepository extends JpaRepository<EntitlementStateTransition, UUID> {

    List<EntitlementStateTransition> findByEntitlementItemIdOrderByTransitionedAtDesc(UUID entitlementItemId);
}
