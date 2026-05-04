package com.figuard.domain.entity;

import com.figuard.domain.enums.BudgetStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBudgetTest {

    private AgentBudget budget(BigDecimal total, BigDecimal spent, BigDecimal reserved,
                               BudgetStatus status, OffsetDateTime expiresAt) {
        AgentBudget b = new AgentBudget();
        b.setTotalLimit(total);
        b.setAmountSpent(spent);
        b.setAmountReserved(reserved);
        b.setStatus(status);
        b.setExpiresAt(expiresAt);
        return b;
    }

    @Test
    void availableAmount_correctlySubtractsSpentAndReserved() {
        AgentBudget b = budget(
            new BigDecimal("500.00"),
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            BudgetStatus.ACTIVE,
            OffsetDateTime.now().plusHours(1)
        );
        assertThat(b.availableAmount()).isEqualByComparingTo("350.00");
    }

    @Test
    void canAccommodate_returnsFalse_whenInsufficientFunds() {
        AgentBudget b = budget(
            new BigDecimal("100.00"),
            new BigDecimal("80.00"),
            new BigDecimal("15.00"),
            BudgetStatus.ACTIVE,
            OffsetDateTime.now().plusHours(1)
        );
        // Available = $5.00; requesting $10.00 should fail
        assertThat(b.canAccommodate(new BigDecimal("10.00"))).isFalse();
    }

    @Test
    // Expiry is handled with a grace window in AuthorizationService (step 5).
    // canAccommodate intentionally does NOT re-check expiry so grace-window requests
    // (within 60s of expiresAt) are not double-denied here.
    void canAccommodate_returnsTrue_whenExpiredButStatusStillActive() {
        AgentBudget b = budget(
            new BigDecimal("500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BudgetStatus.ACTIVE,
            OffsetDateTime.now().minusMinutes(1)   // expired 1 minute ago, but status is ACTIVE
        );
        assertThat(b.canAccommodate(new BigDecimal("10.00"))).isTrue();
    }

    @Test
    void canAccommodate_returnsFalse_whenStatusNotActive() {
        AgentBudget b = budget(
            new BigDecimal("500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BudgetStatus.PAUSED,
            OffsetDateTime.now().plusHours(1)
        );
        assertThat(b.canAccommodate(new BigDecimal("10.00"))).isFalse();
    }

    @Test
    void canAccommodate_returnsTrue_whenExactlyEnoughFunds() {
        AgentBudget b = budget(
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            new BigDecimal("25.00"),
            BudgetStatus.ACTIVE,
            OffsetDateTime.now().plusHours(1)
        );
        // Available = $25.00; requesting exactly $25.00 should pass
        assertThat(b.canAccommodate(new BigDecimal("25.00"))).isTrue();
    }

    @Test
    void isValidSessionToken_returnsTrue_forMatchingHash() {
        AgentBudget b = new AgentBudget();
        b.setSessionTokenHash("abc123hash");
        assertThat(b.isValidSessionToken("abc123hash")).isTrue();
    }

    @Test
    void isValidSessionToken_returnsFalse_forDifferentHash() {
        AgentBudget b = new AgentBudget();
        b.setSessionTokenHash("abc123hash");
        assertThat(b.isValidSessionToken("wronghash")).isFalse();
    }
}
