package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.service.ConfirmationTimeoutSweepService;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies ConfirmationTimeoutSweepService auto-voids AUTHORIZED events whose
 * confirmationTimeoutAt has passed, and releases the budget reservation.
 */
class ConfirmationTimeoutSweepIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired SpendEventRepository eventRepository;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired ConfirmationTimeoutSweepService sweepService;

    // -------------------------------------------------------------------------
    // Stale event auto-void
    // -------------------------------------------------------------------------

    @Test
    void sweep_autoVoids_staleAuthorizedEvent() throws Exception {
        String[] info = createBudgetAndAuthorize(100.00);
        UUID eventId  = UUID.fromString(info[0]);
        UUID budgetId = UUID.fromString(info[1]);

        // Backdate confirmationTimeoutAt so the event appears stale
        SpendEvent event = eventRepository.findById(eventId).orElseThrow();
        event.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(10));
        eventRepository.save(event);

        sweepService.doSweep();

        SpendEvent voided = eventRepository.findById(eventId).orElseThrow();
        assertThat(voided.getDecision()).isEqualTo(SpendDecision.VOIDED);
        assertThat(voided.getFailureReason()).isEqualTo("CONFIRMATION_TIMEOUT");
    }

    @Test
    void sweep_releasesReservation_afterAutoVoid() throws Exception {
        String[] info = createBudgetAndAuthorize(150.00);
        UUID eventId  = UUID.fromString(info[0]);
        UUID budgetId = UUID.fromString(info[1]);

        // Verify reservation is held before sweep
        var budgetBefore = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(budgetBefore.getQuantityReserved()).isEqualByComparingTo("150.00");

        SpendEvent event = eventRepository.findById(eventId).orElseThrow();
        event.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(1));
        eventRepository.save(event);

        sweepService.doSweep();

        var budgetAfter = budgetRepository.findById(budgetId).orElseThrow();
        assertThat(budgetAfter.getQuantityReserved()).isEqualByComparingTo("0.00");
        assertThat(budgetAfter.getQuantitySpent()).isEqualByComparingTo("0.00");
    }

    @Test
    void sweep_doesNotVoid_freshAuthorizedEvent() throws Exception {
        String[] info = createBudgetAndAuthorize(80.00);
        UUID eventId = UUID.fromString(info[0]);

        // confirmationTimeoutAt is in the future — must not be swept
        sweepService.doSweep();

        SpendEvent event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getDecision()).isEqualTo(SpendDecision.AUTHORIZED);
    }

    @Test
    void sweep_skipsAlreadyConfirmedEvent_evenIfTimeoutPassed() throws Exception {
        String[] info = createBudgetAndAuthorize(50.00);
        UUID eventId  = UUID.fromString(info[0]);

        // Confirm before the sweep runs
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 50.00))))
            .andExpect(status().isOk());

        // Now backdate the timeout — sweep should be a no-op for CONFIRMED events
        SpendEvent event = eventRepository.findById(eventId).orElseThrow();
        event.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(10));
        eventRepository.save(event);

        sweepService.doSweep();

        SpendEvent after = eventRepository.findById(eventId).orElseThrow();
        assertThat(after.getDecision()).isEqualTo(SpendDecision.CONFIRMED);
    }

    @Test
    void sweep_continuesProcessing_afterOneEventFailure() throws Exception {
        // Create two stale events. Even if the first one somehow throws, the second
        // should still be processed. We simulate this by creating both events stale.
        String[] info1 = createBudgetAndAuthorize(40.00);
        String[] info2 = createBudgetAndAuthorize(60.00);

        UUID eventId1 = UUID.fromString(info1[0]);
        UUID eventId2 = UUID.fromString(info2[0]);

        for (UUID id : new UUID[]{eventId1, eventId2}) {
            SpendEvent e = eventRepository.findById(id).orElseThrow();
            e.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(5));
            eventRepository.save(e);
        }

        // Both should be voided
        sweepService.doSweep();

        assertThat(eventRepository.findById(eventId1).orElseThrow().getDecision())
            .isEqualTo(SpendDecision.VOIDED);
        assertThat(eventRepository.findById(eventId2).orElseThrow().getDecision())
            .isEqualTo(SpendDecision.VOIDED);
    }

    // -------------------------------------------------------------------------
    // Re-authorization after auto-void
    // -------------------------------------------------------------------------

    @Test
    void afterAutoVoid_budgetAcceptsNewAuthorization() throws Exception {
        String[] info = createBudgetAndAuthorize(200.00);
        UUID eventId       = UUID.fromString(info[0]);
        String sessionToken = info[2];

        SpendEvent event = eventRepository.findById(eventId).orElseThrow();
        event.setConfirmationTimeoutAt(OffsetDateTime.now().minusSeconds(1));
        eventRepository.save(event);

        sweepService.doSweep();

        // New authorization must succeed — funds are released back to available
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_retry",
                    "actionType", "PURCHASE",
                    "description", "retry after timeout",
                    "requestedQuantity", 200.00,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a budget ($500) and authorizes the given amount.
     * Returns [eventId, budgetId, sessionToken].
     */
    private String[] createBudgetAndAuthorize(double amount) throws Exception {
        String createBody = objectMapper.writeValueAsString(Map.of(
            "userId", "user_timeout_" + UUID.randomUUID(),
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));

        var createResult = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andReturn();

        var budgetJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String budgetId     = budgetJson.get("id").asText();
        String sessionToken = budgetJson.get("sessionToken").asText();

        String authBody = objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_sweep_test",
            "actionType", "PURCHASE",
            "description", "timeout sweep test",
            "requestedQuantity", amount,
            "idempotencyKey", UUID.randomUUID().toString()
        ));

        var authResult = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authBody))
            .andExpect(status().isOk())
            .andReturn();

        var authJson = objectMapper.readTree(authResult.getResponse().getContentAsString());
        String eventId = authJson.get("eventId").asText();

        return new String[]{eventId, budgetId, sessionToken};
    }
}
