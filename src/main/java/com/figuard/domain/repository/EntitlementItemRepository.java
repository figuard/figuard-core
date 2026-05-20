package com.figuard.domain.repository;

import com.figuard.domain.entity.EntitlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntitlementItemRepository extends JpaRepository<EntitlementItem, UUID> {

    List<EntitlementItem> findBySubscriptionId(UUID subscriptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EntitlementItem e WHERE e.id = :id")
    Optional<EntitlementItem> findByIdWithLock(@Param("id") UUID id);

    // Renewal sweep — items due for renewal
    @Query("""
        SELECT e FROM EntitlementItem e
        WHERE e.nextRenewalAt <= :now
        ORDER BY e.nextRenewalAt ASC
        """)
    List<EntitlementItem> findItemsDueForRenewal(@Param("now") OffsetDateTime now);
}
