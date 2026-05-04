package com.figuard.domain.repository;

import com.figuard.domain.entity.BudgetAnomalyBaseline;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BudgetAnomalyBaselineRepository extends JpaRepository<BudgetAnomalyBaseline, UUID> {

    Optional<BudgetAnomalyBaseline> findByBudgetId(UUID budgetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BudgetAnomalyBaseline b WHERE b.budget.id = :budgetId")
    Optional<BudgetAnomalyBaseline> findByBudgetIdWithLock(@Param("budgetId") UUID budgetId);
}
