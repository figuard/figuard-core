package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Edge cases around the confirm endpoint that are not covered elsewhere:
 * - Confirm on already-confirmed event (409)
 * - Confirm on voided event (409)
 * - Confirming after fail (409)
 * - Multiple authorizations on same budget; confirm each independently
 */
class ConfirmEdgeCasesIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired AgentBudgetRepository budgetRepository;

    @Test
    void confirm_returns409_whenEventAlreadyConfirmed() throws Exception {
        String[] info = authorizeAmount(100.00);
        String eventId = info[0];

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 100.00))))
            .andExpect(status().isOk());

        // Second confirm — must fail
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 100.00))))
            .andExpect(status().isConflict());
    }

    @Test
    void confirm_returns409_whenEventAlreadyVoided() throws Exception {
        String[] info = authorizeAmount(80.00);
        String eventId = info[0];

        // Void the event
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "cancelled"))))
            .andExpect(status().isOk());

        // Confirm must fail — event is no longer AUTHORIZED
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 80.00))))
            .andExpect(status().isConflict());
    }

    @Test
    void confirm_returns404_forUnknownEventId() throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/confirm", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 50.00))))
            .andExpect(status().isNotFound());
    }

    @Test
    void twoAuthorizationsOnSameBudget_confirmInOrder_correctlyUpdatesBalance() throws Exception {
        // Budget: $500
        // Auth1: $150, Auth2: $200, then confirm both = spent $350
        String[] budget = createBudget();
        String sessionToken = budget[1];
        String budgetId     = budget[0];

        String eventId1 = authorize(sessionToken, 150.00);
        String eventId2 = authorize(sessionToken, 200.00);

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId1)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 150.00))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId2)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 200.00))))
            .andExpect(status().isOk());

        AgentBudget b = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        assertThat(b.getAmountSpent()).isEqualByComparingTo("350.00");
        assertThat(b.getAmountReserved()).isEqualByComparingTo("0.00");
        assertThat(b.availableAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void fail_returns409_whenEventAlreadyFailed() throws Exception {
        String[] info = authorizeAmount(60.00);
        String eventId = info[0];

        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "declined"))))
            .andExpect(status().isOk());

        // Second fail — must fail
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "declined again"))))
            .andExpect(status().isConflict());
    }

    @Test
    void fail_returns409_whenEventAlreadyConfirmed() throws Exception {
        String[] info = authorizeAmount(90.00);
        String eventId = info[0];

        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedAmount", 90.00))))
            .andExpect(status().isOk());

        // Fail after confirm — must be rejected
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "too late"))))
            .andExpect(status().isConflict());
    }

    @Test
    void failedEvent_releasesReservation_toAvailableAmount() throws Exception {
        String[] budget = createBudget();
        String sessionToken = budget[1];
        String budgetId     = budget[0];

        String eventId = authorize(sessionToken, 300.00);

        // Verify reservation is held
        AgentBudget before = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        assertThat(before.getAmountReserved()).isEqualByComparingTo("300.00");
        assertThat(before.availableAmount()).isEqualByComparingTo("200.00");

        // Fail the event
        mockMvc.perform(post("/api/v1/events/{id}/fail", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "PAYMENT_DECLINED"))))
            .andExpect(status().isOk());

        AgentBudget after = budgetRepository.findById(UUID.fromString(budgetId)).orElseThrow();
        assertThat(after.getAmountReserved()).isEqualByComparingTo("0.00");
        assertThat(after.getAmountSpent()).isEqualByComparingTo("0.00");
        assertThat(after.availableAmount()).isEqualByComparingTo("500.00");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String[] createBudget() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "userId", "user_confirm_edge_" + UUID.randomUUID(),
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));

        var result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[]{json.get("id").asText(), json.get("sessionToken").asText()};
    }

    /** Creates a budget and immediately authorizes; returns [eventId, budgetId, sessionToken]. */
    private String[] authorizeAmount(double amount) throws Exception {
        String[] budget = createBudget();
        String eventId = authorize(budget[1], amount);
        return new String[]{eventId, budget[0], budget[1]};
    }

    private String authorize(String sessionToken, double amount) throws Exception {
        var result = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_edge_test",
                    "actionType", "PURCHASE",
                    "description", "confirm edge test",
                    "requestedAmount", amount,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("eventId").asText();
    }
}
