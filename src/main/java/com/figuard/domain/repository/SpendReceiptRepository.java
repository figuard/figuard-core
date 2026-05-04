package com.figuard.domain.repository;

import com.figuard.domain.entity.SpendReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpendReceiptRepository extends JpaRepository<SpendReceipt, UUID> {

    Optional<SpendReceipt> findByBudgetId(UUID budgetId);

    Optional<SpendReceipt> findByReceiptToken(String receiptToken);
}
