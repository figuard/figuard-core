package com.figuard.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-category spend cap for a DelegatedToken.
 *
 * Mirrors BudgetAllocation but at the delegation level. quantitySpent and
 * quantityReserved track only spend made via this specific delegation token,
 * independently from the parent fleet allocation's counters.
 *
 * Lock order (always respect this to avoid deadlocks):
 *   AgentBudget (fleet) → DelegatedTokenAllocation → BudgetAllocation (fleet allocation)
 */
@Entity
@Table(name = "delegated_token_allocations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"delegated_token_id", "category"}))
@Getter @Setter @NoArgsConstructor
public class DelegatedTokenAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegated_token_id", nullable = false)
    private DelegatedToken delegatedToken;

    /** Must match the category label used in BudgetAllocation.category (lowercased). */
    @Column(nullable = false, length = 255)
    private String category;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantitySpent = BigDecimal.ZERO;     // CONFIRMED events only

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityReserved = BigDecimal.ZERO;  // AUTHORIZED events awaiting confirmation

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public BigDecimal availableQuantity() {
        return totalLimit.subtract(quantitySpent).subtract(quantityReserved);
    }

    public boolean canAccommodate(BigDecimal requestedQuantity) {
        return availableQuantity().compareTo(requestedQuantity) >= 0;
    }
}
