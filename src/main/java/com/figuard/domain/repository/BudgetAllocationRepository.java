package com.figuard.domain.repository;

import com.figuard.domain.entity.BudgetAllocation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetAllocationRepository extends JpaRepository<BudgetAllocation, UUID> {

    // ORDER BY createdAt is critical — first-match semantics must be deterministic
    List<BudgetAllocation> findByParentBudgetIdOrderByCreatedAtAsc(UUID budgetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BudgetAllocation a WHERE a.id = :id")
    Optional<BudgetAllocation> findByIdWithLock(@Param("id") UUID id);
}
