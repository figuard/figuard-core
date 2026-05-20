package com.figuard.domain.entity;

import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.OveragePolicy;
import com.figuard.domain.enums.RenewalPeriod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "entitlement_items")
@Getter @Setter @NoArgsConstructor
public class EntitlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(nullable = false)
    private String name;

    /**
     * Human-readable unit for display (e.g. "USD", "tokens", "API calls").
     * Not interpreted by the enforcement engine — enforcement uses limitQuantity
     * in the same unit as SpendEvent.requestedQuantity.
     */
    @Column(nullable = false, length = 30)
    private String limitUnit;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal limitQuantity;

    /**
     * Percentage at which APPROACHING state is triggered (default 80).
     * Valid range: 1–99.
     */
    @Column(nullable = false)
    private int warnAtPercentage = 80;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RenewalPeriod renewalPeriod;

    @Column(nullable = false)
    private OffsetDateTime nextRenewalAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OveragePolicy overagePolicy = OveragePolicy.BLOCK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntitlementState state = EntitlementState.NORMAL;

    /**
     * Running total consumed in the current period. Reset to zero on renewal.
     */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal currentPeriodConsumed = BigDecimal.ZERO;

    /**
     * Timestamp of the last state transition — used to deduplicate webhook firing.
     * Only one ENTITLEMENT_STATE_CHANGED webhook fires per state per period.
     */
    @Column
    private OffsetDateTime lastStateTransitionAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public BigDecimal remaining() {
        return limitQuantity.subtract(currentPeriodConsumed).max(BigDecimal.ZERO);
    }

    public boolean canAccommodate(BigDecimal requestedQuantity) {
        return remaining().compareTo(requestedQuantity) >= 0;
    }

    public int consumedPercentage() {
        if (limitQuantity.compareTo(BigDecimal.ZERO) == 0) return 100;
        return currentPeriodConsumed
                .multiply(new BigDecimal("100"))
                .divide(limitQuantity, 0, java.math.RoundingMode.FLOOR)
                .intValue();
    }
}
