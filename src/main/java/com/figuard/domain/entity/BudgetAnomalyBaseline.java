package com.figuard.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-budget rolling baseline for anomaly detection.
 *
 * Updated asynchronously after every CONFIRMED spend event using Welford's
 * online algorithm, which maintains a running mean without loading full history.
 *
 * The anomaly check compares requestedAmount against meanAmount * multiplier.
 * stdDevAmount is tracked for observability but is not used in the check.
 */
@Entity
@Table(name = "budget_anomaly_baselines")
@Getter @Setter @NoArgsConstructor
public class BudgetAnomalyBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false, unique = true)
    private AgentBudget budget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private int sampleCount = 0;

    @Column(precision = 19, scale = 4)
    private BigDecimal meanAmount;

    // Running std dev — informational only, not used in the anomaly check
    @Column(precision = 19, scale = 4)
    private BigDecimal stdDevAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal minAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxAmount;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime lastUpdatedAt;
}
