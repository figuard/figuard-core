package com.figuard.domain.repository;

import com.figuard.domain.entity.DelegatedToken;
import com.figuard.domain.enums.DelegatedTokenStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DelegatedTokenRepository extends JpaRepository<DelegatedToken, UUID> {

    /**
     * Token lookup for the authorize path — only returns ACTIVE tokens.
     * No pessimistic lock here; the lock is on the parent AgentBudget (loaded next).
     */
    @Query("SELECT t FROM DelegatedToken t WHERE t.sessionTokenHash = :hash AND t.status = 'ACTIVE'")
    Optional<DelegatedToken> findActiveBySessionTokenHash(@Param("hash") String hash);

    List<DelegatedToken> findByParentBudgetId(UUID parentBudgetId);

    List<DelegatedToken> findByParentBudgetIdAndStatus(UUID parentBudgetId, DelegatedTokenStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DelegatedToken t WHERE t.id = :id")
    Optional<DelegatedToken> findByIdWithLock(@Param("id") UUID id);
}
