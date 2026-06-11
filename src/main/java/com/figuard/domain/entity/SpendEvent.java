package com.figuard.domain.entity;

import com.figuard.domain.enums.SpendDecision;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "spend_events")
@Getter @Setter @NoArgsConstructor
public class SpendEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private AgentBudget budget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_event_id")
    private SpendEvent parentEvent;

    @Column(nullable = false)
    private UUID rootBudgetId;                  // always the top-level budget UUID

    /**
     * Nullable — only set when the spend event draws from a subscription entitlement item.
     * Null means the event was authorized against a standalone budget (unknown-user path).
     */
    @Column
    private UUID entitlementItemId;

    @Column(nullable = false)
    private String agentId;                     // observability only, not enforcement

    private String agentType;

    @Column(nullable = false)
    private String actionType;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedQuantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal confirmedQuantity;       // actual quantity charged; set by /confirm

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(columnDefinition = "CHAR(3)")
    private String currency;

    // Structured intent — enforcement fields (NOT intentContext)
    private String claimedCategory;             // agent's declared category e.g. "flight"
    private String claimedItemType;             // agent's declared item type e.g. "airline_ticket" — STRICT only

    // intentContext is kept for logging/audit/human-readable trail ONLY.
    // It is NEVER used to make authorization decisions.
    @Column(length = 1000)
    private String intentContext;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_id")
    private BudgetAllocation allocation;

    @Column(nullable = false)
    private String idempotencyKey;

    // The real-world entity this spend relates to — invoice_123, order_456, booking_789.
    // Optional but strongly recommended. Enables entity-scoped dedup and querying
    // without encoding entity context into the idempotency key.
    private String entityId;

    private String externalTransactionId;       // set by /confirm; blocks void without refund

    @Column(length = 500)
    private String denialReason;

    @Column(length = 1000)
    private String denialMessage;

    @Column(length = 500)
    private String failureReason;

    private OffsetDateTime confirmationTimeoutAt;

    // false for a reserve=false tree-root / coordinator marker: the event holds no
    // capacity, so it is excluded from "currently reserved" sums. Defaults to true.
    @Column(nullable = false)
    private boolean reserved = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SpendDecision decision;

    // Links all events from one agent run (e.g. one LangChain invocation).
    // Set by the caller on authorize — propagated to every event in the run.
    // Indexed; filterable on the ledger endpoint via ?traceId=...
    @Column(length = 255)
    private String traceId;

    // -------------------------------------------------------------------------
    // Causal chain tracking — per-chain spend cap
    // -------------------------------------------------------------------------

    /**
     * Denormalized pointer to the root of this causal chain.
     *
     * Root events (parentEvent == null): chainRootEventId = this.id (self-referential)
     * Child events:                       chainRootEventId = parentEvent.chainRootEventId
     *
     * Null only for events created before migration V23 (legacy events with no chain root).
     * The subtree cap check skips events where chainRootEventId is null, so legacy
     * chains are unaffected.
     */
    @Column(name = "chain_root_event_id")
    private UUID chainRootEventId;

    /**
     * Optional per-chain spend cap, set by the caller on root authorize() calls.
     *
     * When set, all AUTHORIZED + CONFIRMED events sharing the same chainRootEventId
     * are summed before each authorization. If the projected total would exceed this
     * value, the request is denied with SUBTREE_CAP_EXCEEDED.
     *
     * Only meaningful on root events (chainRootEventId == id). Always null on child events.
     */
    @Column(precision = 19, scale = 4)
    private BigDecimal maxSubtreeQuantity;

    /**
     * Set when this event was authorized via a DelegatedToken rather than a direct budget token.
     * Used by the lifecycle service (confirm/fail/void) to update the per-token category cap counters.
     * NULL for events created via a normal session token.
     */
    @Column
    private UUID delegatedTokenId;

    /**
     * Origin of this event: null for standard AGENT events; "HUMAN" or "EXTERNAL" for events
     * recorded retroactively via POST /api/v1/events/external (e.g. a manual QuickBooks entry).
     */
    @Column(length = 20)
    private String eventSource;

    /**
     * When the action actually occurred in the real world.
     * Set only on external events; null for standard events (createdAt is accurate).
     */
    @Column
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;                       // CRITICAL: prevents confirm/fail/void race conditions

    // Returns true only when this event cannot be further transitioned
    public boolean isTerminal() {
        return decision == SpendDecision.CONFIRMED
            || decision == SpendDecision.FAILED
            || decision == SpendDecision.DENIED
            || decision == SpendDecision.VOIDED;
    }

    public boolean canBeConfirmed() { return decision == SpendDecision.AUTHORIZED; }
    public boolean canBeFailed()    { return decision == SpendDecision.AUTHORIZED; }
    // AUTHORIZED: normal reservation rollback
    // CONFIRMED: downstream system rollback (payment processor reversal, etc.)
    public boolean canBeVoided()    { return decision == SpendDecision.AUTHORIZED || decision == SpendDecision.CONFIRMED; }
}
