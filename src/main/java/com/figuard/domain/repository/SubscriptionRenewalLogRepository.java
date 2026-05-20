package com.figuard.domain.repository;

import com.figuard.domain.entity.SubscriptionRenewalLog;
import com.figuard.domain.enums.RenewalResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionRenewalLogRepository extends JpaRepository<SubscriptionRenewalLog, UUID> {

    List<SubscriptionRenewalLog> findBySubscriptionIdOrderByRenewalExecutedAtDesc(UUID subscriptionId);

    List<SubscriptionRenewalLog> findByEntitlementItemIdOrderByRenewalExecutedAtDesc(UUID entitlementItemId);

    // Used by v2 reconciliation sweep to find failed renewals needing manual intervention
    List<SubscriptionRenewalLog> findByResult(RenewalResult result);
}
