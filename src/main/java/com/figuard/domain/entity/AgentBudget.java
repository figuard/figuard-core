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

    @Column(nullable = false, length = 1000)
    private String intentContext;

    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] intentTags;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "CHAR(3)")
    private String currency = "USD";

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amountSpent = BigDecimal.ZERO;       // CONFIRMED events only

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amountReserved = BigDecimal.ZERO;    // AUTHORIZED events awaiting confirmation

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetStatus status = BudgetStatus.ACTIVE;

    @Column(precision = 19, scale = 4)
    private BigDecimal softLimit;

    // Optional per-transaction ceiling. When non-null, any single authorize where
    // requestedAmount > maxTransactionAmount is denied immediately, regardless of
    // available funds. Aggregate cap (totalLimit) is checked separately.
    @Column(precision = 19, scale = 4)
    private BigDecimal maxTransactionAmount;

    @Column(nullable = false)
    private boolean entityDedupEnabled = false;

    @Column(nullable = false)
    private boolean anomalyDetectionEnabled = false;

    @Column(precision = 5, scale = 2)
    private BigDecimal anomalyPauseThresholdMultiplier = new BigDecimal("3.00");

    @Column
    private Integer anomalyMinSampleSize = 5;

    @Column(length = 2000)
    private String anomalyAlertWebhookUrl;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

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

    // Both confirmed spend and in-flight reservations reduce available funds.
    // A $100 authorize + $50 confirm still leaves only $350 of a $500 budget.
    public BigDecimal availableAmount() {
        return totalLimit.subtract(amountSpent).subtract(amountReserved);
    }

    // Expiry is checked separately in AuthorizationService (with a grace window).
    // This method only checks status and available funds.
    public boolean canAccommodate(BigDecimal requestedAmount) {
        return status == BudgetStatus.ACTIVE
            && availableAmount().compareTo(requestedAmount) >= 0;
    }

    // Caller passes SHA-256(presentedToken); constant-time compare prevents timing attacks.
    public boolean isValidSessionToken(String presentedTokenHash) {
        return MessageDigest.isEqual(
            sessionTokenHash.getBytes(StandardCharsets.UTF_8),
            presentedTokenHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
