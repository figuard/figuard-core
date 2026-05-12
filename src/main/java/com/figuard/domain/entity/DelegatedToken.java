package com.figuard.domain.entity;

import com.figuard.domain.enums.DelegatedTokenStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A scoped delegation token tied to a parent AgentBudget.
 *
 * Delegation tokens allow a fleet orchestrator to issue a restricted credential to each
 * sub-agent. The sub-agent calls /authorize with the delegation token exactly as it would
 * with a normal session token — the server resolves the parent budget and enforces both
 * the per-token category caps (DelegatedTokenAllocation) and the fleet-level allocations.
 *
 * Key properties:
 * - One parent budget, many delegation tokens (one per sub-agent / customer order)
 * - Per-category spend caps tracked independently from the parent allocation
 * - Revocation is instant — any subsequent authorize attempt is rejected
 */
@Entity
@Table(name = "delegated_tokens")
@Getter @Setter @NoArgsConstructor
public class DelegatedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_budget_id", nullable = false)
    private AgentBudget parentBudget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, unique = true, length = 64)
    private String sessionTokenHash;

    @Column(nullable = false, length = 12)
    private String sessionTokenPrefix;

    /** Human-readable label — e.g. "customer-order-123", "refund-agent-user_456". */
    @Column(nullable = false, length = 255)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DelegatedTokenStatus status = DelegatedTokenStatus.ACTIVE;

    @Column
    private OffsetDateTime revokedAt;

    /**
     * Per-category caps. Only categories with a DelegatedTokenAllocation are cap-enforced.
     * Categories not listed here pass through directly to the parent fleet allocation.
     */
    @OneToMany(mappedBy = "delegatedToken", cascade = CascadeType.ALL, fetch = FetchType.LAZY,
               orphanRemoval = true)
    private List<DelegatedTokenAllocation> caps;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;
}
