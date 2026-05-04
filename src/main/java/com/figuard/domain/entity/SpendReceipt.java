package com.figuard.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "spend_receipts")
@Getter @Setter @NoArgsConstructor
public class SpendReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private AgentBudget budget;

    @Column(nullable = false, unique = true, length = 32, columnDefinition = "varchar(32)")
    private String receiptToken;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime generatedAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
