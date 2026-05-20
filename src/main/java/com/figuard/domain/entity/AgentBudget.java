package com.figuard.domain.entity;

import com.figuard.domain.enums.BudgetStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "agent_budgets")
@Getter @Setter @NoArgsConstructor
public class AgentBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true, length = 64)
    private String sessionTokenHash;

    @Column(nullable = false, length = 12)
    private String sessionTokenPrefix;

    private String externalReference;

    /**
     * Set when this budget is entitlement-backed (known-user path).
     * Null = standalone budget (anonymous/unknown-user path — existing behavior).
     * When set, enforcement happens at the EntitlementItem level, not at totalLimit.
     */
    @Column
    private UUID subscriptionId;

    @Column
    private UUID entitlementItemId;

    @Column(nullable = false, length = 1000)
    private String intentContext;

    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] intentTags;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    // Monetary budgets: 3-letter ISO code (e.g. "USD"). Null for resource budgets.
    // Exactly one of currency or unit must be set.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(columnDefinition = "CHAR(3)")
    private String currency;

    // Resource budgets: free-form label (e.g. "tokens", "api_calls", "gpu_hours").
    // Null for monetary budgets.
    @Column(length = 50)
    private String unit;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantitySpent = BigDecimal.ZERO;       // CONFIRMED events only

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityReserved = BigDecimal.ZERO;    // AUTHORIZED events awaiting confirmation

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetStatus status = BudgetStatus.ACTIVE;

    @Column(precision = 19, scale = 4)
    private BigDecimal softLimit;

    // Optional per-transaction ceiling. When non-null, any single authorize where
    // requestedQuantity > maxTransactionQuantity is denied immediately.
    @Column(precision = 19, scale = 4)
    private BigDecimal maxTransactionQuantity;

    @Column(nullable = false)
    private boolean entityDedupEnabled = false;

    @Column(nullable = false)
    private boolean anomalyDetectionEnabled = false;

    /**
     * When true (default), an anomaly automatically pauses the budget and blocks all further
     * spend until manually resumed. When false, the anomaly is recorded and the ANOMALY_DETECTED
     * webhook fires, but the request is still denied and the budget stays ACTIVE — advisory mode.
     * Set to false for high-throughput workloads where a single unusual spike should not halt
     * the entire agent fleet.
     */
    @Column(nullable = false)
    private boolean autoPauseOnAnomaly = true;

    @Column(precision = 5, scale = 2)
    private BigDecimal anomalyPauseThresholdMultiplier = new BigDecimal("3.00");

    @Column
    private Integer anomalyMinSampleSize = 5;

    @Column(length = 2000)
    private String anomalyAlertWebhookUrl;

    // Lazy auto-expiry for stale reservations. When set, AUTHORIZED events older
    // than this window are excluded from the available-quantity calculation.
    // Orphaned reservations age out without a background sweep job.
    // Null = no expiry (reservation holds until explicitly voided/failed/confirmed).
    @Column
    private Integer authorizationExpirySeconds;

    // Rolling-window velocity controls. All three are optional (null = unlimited).
    // Checked after expiry, before category matching.
    @Column
    private Integer velocityMaxPerMinute;

    @Column(precision = 19, scale = 4)
    private BigDecimal velocityMaxAmountPerHour;

    @Column
    private Integer velocityMaxPerDay;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    /**
     * Set to true once the BUDGET_EXPIRING_SOON webhook has been dispatched.
     * Prevents re-firing on every sweep pass after the initial notification.
     */
    @Column(nullable = false)
    private boolean expiringSoonNotified = false;

    @Column(nullable = false)
    private OffsetDateTime firstAuthorizeDeadline;

    @Column(length = 64)
    private String previousSessionTokenHash;

    private OffsetDateTime tokenRotationExpiresAt;

    private OffsetDateTime cancelledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @OneToMany(mappedBy = "parentBudget", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BudgetAllocation> allocations;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    public BigDecimal availableQuantity() {
        return totalLimit.subtract(quantitySpent).subtract(quantityReserved);
    }

    /** Variant used when authorization_expiry_seconds is set — passes in the
     *  DB-computed effective reserved (excluding expired authorizations). */
    public BigDecimal availableQuantityWith(BigDecimal effectiveReserved) {
        return totalLimit.subtract(quantitySpent).subtract(effectiveReserved);
    }

    public boolean canAccommodate(BigDecimal requestedQuantity) {
        return status == BudgetStatus.ACTIVE
            && availableQuantity().compareTo(requestedQuantity) >= 0;
    }

    public boolean canAccommodateWith(BigDecimal requestedQuantity, BigDecimal effectiveReserved) {
        return status == BudgetStatus.ACTIVE
            && availableQuantityWith(effectiveReserved).compareTo(requestedQuantity) >= 0;
    }

    public boolean isValidSessionToken(String presentedTokenHash) {
        return MessageDigest.isEqual(
            sessionTokenHash.getBytes(StandardCharsets.UTF_8),
            presentedTokenHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** True when budget is monetary (currency is set). */
    public boolean isMonetary() {
        return currency != null && !currency.isBlank();
    }
}
