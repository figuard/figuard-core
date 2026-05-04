package com.figuard.domain.repository;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.enums.BudgetStatus;
import jakarta.persistence.LockModeType;
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
}
