package com.figuard.domain.repository;

import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.SpendDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpendEventRepository extends JpaRepository<SpendEvent, UUID> {

    Optional<SpendEvent> findByBudgetIdAndIdempotencyKey(UUID budgetId, String idempotencyKey);

    List<SpendEvent> findByBudgetIdOrderByCreatedAtAsc(UUID budgetId);

    // Paginated ledger — all events for a budget, newest first
    Page<SpendEvent> findByBudgetIdOrderByCreatedAtDesc(UUID budgetId, Pageable pageable);

    // Paginated ledger filtered by decision
    Page<SpendEvent> findByBudgetIdAndDecisionOrderByCreatedAtDesc(UUID budgetId, SpendDecision decision, Pageable pageable);

    // Full list filtered by decision — used by receipt service (no pagination needed for receipts)
    List<SpendEvent> findByBudgetIdAndDecisionOrderByCreatedAtDesc(UUID budgetId, SpendDecision decision);

    // Returns only root events (no parent) — used to walk causal chains
    List<SpendEvent> findByParentEventIdIsNullAndBudgetId(UUID budgetId);

    // Lifecycle operations (confirm/fail/void) use pessimistic lock to prevent concurrent state changes
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SpendEvent e WHERE e.id = :id")
    Optional<SpendEvent> findByIdWithLock(@Param("id") UUID id);

    // Sweep: AUTHORIZED events whose confirmation window has closed
    @Query("SELECT e FROM SpendEvent e WHERE e.decision = 'AUTHORIZED' AND e.confirmationTimeoutAt < :now")
    List<SpendEvent> findStaleAuthorizations(@Param("now") OffsetDateTime now);

    // Child events sharing the same parent — used by voidChildEvents
    List<SpendEvent> findByParentEventId(UUID parentEventId);

    // Entity dedup check — finds an existing AUTHORIZED or CONFIRMED event for the same entityId on this budget
    List<SpendEvent> findByBudgetIdAndEntityIdAndDecisionIn(UUID budgetId, String entityId, List<SpendDecision> decisions);

    // Sweep heartbeat gauge — count of all currently AUTHORIZED (in-flight) events
    @Query("SELECT COUNT(e) FROM SpendEvent e WHERE e.decision = 'AUTHORIZED'")
    long countPendingAuthorizations();

    // Ledger integrity — sum of requestedAmount for AUTHORIZED events per budget
    @Query("SELECT COALESCE(SUM(e.requestedAmount), 0) FROM SpendEvent e WHERE e.budget.id = :budgetId AND e.decision = 'AUTHORIZED'")
    java.math.BigDecimal sumAuthorizedAmountByBudget(@Param("budgetId") UUID budgetId);

    // Ledger integrity — sum of confirmedAmount for CONFIRMED events per budget
    @Query("SELECT COALESCE(SUM(e.confirmedAmount), 0) FROM SpendEvent e WHERE e.budget.id = :budgetId AND e.decision = 'CONFIRMED'")
    java.math.BigDecimal sumConfirmedAmountByBudget(@Param("budgetId") UUID budgetId);
}
