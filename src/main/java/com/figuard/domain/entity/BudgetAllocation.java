package com.figuard.domain.entity;

import com.figuard.domain.enums.AllocationStatus;
import com.figuard.domain.enums.EnforcementMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "budget_allocations")
@Getter @Setter @NoArgsConstructor
public class BudgetAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_budget_id", nullable = false)
    private AgentBudget parentBudget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String category;                    // primary label e.g. "flight", "hotel"

    @Column(columnDefinition = "text[]", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] allowedCategories;         // agent claimedCategory must exactly match one of these

    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] forbiddenItemTypes;        // optional blocklist for STRICT mode; null = no restrictions

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnforcementMode enforcementMode = EnforcementMode.CATEGORY_CONSTRAINED;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amountSpent = BigDecimal.ZERO;       // CONFIRMED events only

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amountReserved = BigDecimal.ZERO;    // AUTHORIZED events awaiting confirmation

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "CHAR(3)")
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AllocationStatus status = AllocationStatus.ACTIVE;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public BigDecimal availableAmount() {
        return totalLimit.subtract(amountSpent).subtract(amountReserved);
    }

    public boolean canAccommodate(BigDecimal requestedAmount) {
        return status == AllocationStatus.ACTIVE
            && availableAmount().compareTo(requestedAmount) >= 0;
    }

    /**
     * Checks whether the agent's declared category routes to this allocation.
     * Direct equality — no fuzzy matching, no keyword inference, no case folding.
     * intentContext is NOT consulted here. It is for logging only.
     */
    public boolean matchesCategory(String claimedCategory) {
        if (claimedCategory == null || claimedCategory.isBlank()) return false;
        return Arrays.asList(allowedCategories).contains(claimedCategory);
    }

    /**
     * Returns true if the agent's declared item type is explicitly blocked.
     * Only evaluated when enforcementMode == STRICT.
     * Returns false when: mode != STRICT, forbiddenItemTypes is null/empty,
     * or claimedItemType is null/blank.
     */
    public boolean isItemTypeForbidden(String claimedItemType) {
        if (enforcementMode != EnforcementMode.STRICT) return false;
        if (forbiddenItemTypes == null || forbiddenItemTypes.length == 0) return false;
        if (claimedItemType == null || claimedItemType.isBlank()) return false;
        return Arrays.asList(forbiddenItemTypes).contains(claimedItemType);
    }
}
