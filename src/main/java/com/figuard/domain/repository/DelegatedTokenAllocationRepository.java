package com.figuard.domain.repository;

import com.figuard.domain.entity.DelegatedTokenAllocation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DelegatedTokenAllocationRepository extends JpaRepository<DelegatedTokenAllocation, UUID> {

    /**
     * Pessimistic lock for authorize path — delegate cap enforcement.
     * Always acquired BEFORE locking the parent BudgetAllocation to maintain consistent lock order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DelegatedTokenAllocation a WHERE a.delegatedToken.id = :tokenId AND a.category = :category")
    Optional<DelegatedTokenAllocation> findByTokenIdAndCategoryWithLock(
        @Param("tokenId") UUID tokenId,
        @Param("category") String category);

    /**
     * Pessimistic lock for lifecycle path (confirm/fail/void).
     * Used when the SpendEvent carries a delegatedTokenId and we need to update the cap counters.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DelegatedTokenAllocation a WHERE a.id = :id")
    Optional<DelegatedTokenAllocation> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT a FROM DelegatedTokenAllocation a WHERE a.delegatedToken.id = :tokenId AND a.category = :category")
    Optional<DelegatedTokenAllocation> findByTokenIdAndCategory(
        @Param("tokenId") UUID tokenId,
        @Param("category") String category);

    /**
     * Pessimistic lock — fallback for flat-budget delegation when no claimedCategory is set.
     * Returns any single cap on this token so the flat path can still enforce the overall limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DelegatedTokenAllocation a WHERE a.delegatedToken.id = :tokenId ORDER BY a.createdAt ASC")
    Optional<DelegatedTokenAllocation> findFirstByTokenIdWithLock(@Param("tokenId") UUID tokenId);
}
