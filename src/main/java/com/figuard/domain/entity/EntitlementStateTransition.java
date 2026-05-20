package com.figuard.domain.entity;

import com.figuard.domain.enums.EntitlementState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "entitlement_state_transitions")
@Getter @Setter @NoArgsConstructor
public class EntitlementStateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entitlement_item_id", nullable = false)
    private EntitlementItem entitlementItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntitlementState fromState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntitlementState toState;

    /** Percentage consumed at transition time — useful for audit and debugging. */
    @Column(nullable = false)
    private int consumedPercentageAtTransition;

    @Column(nullable = false, length = 50)
    private String triggerReason; // e.g. "CONSUMPTION", "MANUAL_PAUSE", "RENEWAL"

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime transitionedAt;
}
