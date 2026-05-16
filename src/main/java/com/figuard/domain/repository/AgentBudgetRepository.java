package com.figuard.domain.repository;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.BudgetStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentBudgetRepository extends JpaRepository<AgentBudget, UUID> {

    // Primary token lookup — checks both the current hash and the previous hash during
    // the rotation grace window. Pessimistic lock held for the full authorize transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b FROM AgentBudget b
        WHERE b.sessionTokenHash = :hash
           OR (b.previousSessionTokenHash = :hash
               AND b.tokenRotationExpiresAt > :now)
        """)
    Optional<AgentBudget> findBySessionTokenHashOrPrevious(
        @Param("hash") String hash,
        @Param("now") OffsetDateTime now);

    // Used by ApiKeyAuthFilter — not called in a transaction, no lock needed
    @Query("SELECT b FROM AgentBudget b WHERE b.sessionTokenHash = :hash")
    Optional<AgentBudget> findBySessionTokenHash(@Param("hash") String hash);

    // Used for cancellation — lock to prevent concurrent state changes
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AgentBudget b WHERE b.id = :id")
    Optional<AgentBudget> findByIdWithLock(@Param("id") UUID id);

    // Sweep job: budgets that never received a first authorize call.
    // Cannot be a derived method — requires correlated NOT EXISTS subquery.
    @Query("SELECT b FROM AgentBudget b WHERE b.status = 'ACTIVE' " +
           "AND b.firstAuthorizeDeadline < :deadline " +
           "AND NOT EXISTS (SELECT e FROM SpendEvent e WHERE e.budget = b)")
    List<AgentBudget> findOrphanedBudgets(@Param("deadline") OffsetDateTime deadline);

    // Integrity sweep: page through all non-terminal budgets without loading millions at once.
    // Spring Data derives the query from the method name — no @Query needed.
    List<AgentBudget> findByStatusIn(Collection<BudgetStatus> statuses, Pageable pageable);

    // Dashboard list: all budgets for a tenant, optionally filtered by status, newest first.
    Page<AgentBudget> findByTenant(Tenant tenant, Pageable pageable);

    Page<AgentBudget> findByTenantAndStatus(Tenant tenant, BudgetStatus status, Pageable pageable);

    // Default list (no status filter): exclude CANCELLED so the dashboard shows live budgets
    // by default. Callers pass ?status=CANCELLED to opt in to seeing cancelled budgets.
    Page<AgentBudget> findByTenantAndStatusNot(Tenant tenant, BudgetStatus excludedStatus, Pageable pageable);

    // Batch cancel: find all budgets in a given set of IDs for this tenant
    @Query("SELECT b FROM AgentBudget b WHERE b.tenant = :tenant AND b.id IN :ids")
    List<AgentBudget> findByTenantAndIdIn(@Param("tenant") Tenant tenant, @Param("ids") Collection<UUID> ids);

    // Idempotent budget creation: look up an active/paused budget by externalReference.
    // Terminal budgets (EXPIRED, CANCELLED, EXHAUSTED) are excluded so a new budget can
    // always be created after the previous one finishes.
    Optional<AgentBudget> findByTenantAndExternalReferenceAndStatusIn(
        Tenant tenant, String externalReference, Collection<BudgetStatus> statuses);

    // Expiry-soon sweep: find ACTIVE or PAUSED budgets expiring within the notification
    // window that haven't been notified yet. :windowStart is typically now+55min,
    // :windowEnd is now+65min, so the 5-minute sweep fires the notification exactly once.
    @Query("""
        SELECT b FROM AgentBudget b
        WHERE b.status IN ('ACTIVE', 'PAUSED')
          AND b.expiresAt BETWEEN :windowStart AND :windowEnd
          AND b.expiringSoonNotified = false
        """)
    List<AgentBudget> findExpiringSoon(
        @Param("windowStart") OffsetDateTime windowStart,
        @Param("windowEnd")   OffsetDateTime windowEnd);

    // Customer view: all budgets for a specific userId, with optional status filtering.
    Page<AgentBudget> findByTenantAndUserId(Tenant tenant, String userId, Pageable pageable);

    Page<AgentBudget> findByTenantAndUserIdAndStatus(Tenant tenant, String userId, BudgetStatus status, Pageable pageable);

    Page<AgentBudget> findByTenantAndUserIdAndStatusNot(Tenant tenant, String userId, BudgetStatus excludedStatus, Pageable pageable);
}
