package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that a partial confirmation (confirmedAmount < requestedAmount)
 * releases the full reservation and debits only the confirmed amount,
 * returning the remainder to availableAmount.
 */
class PartialConfirmationIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void confirm_withPartialAmount_releasesFullReservationAndDebitsConfirmedAmount() throws Exception {
        // Create a budget with $200.00 total
        String createResp = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_partial_confirm",
                    "intentContext", "partial confirm test",
                    "totalLimit", 200.00,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var createJson = objectMapper.readTree(createResp);
        String budgetId    = createJson.get("id").asText();
        String sessionToken = createJson.get("sessionToken").asText();

        // Authorize $89.00 — availableAmount should drop from $200.00 to $111.00
        String authResp = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_partial_test",
                    "actionType", "PURCHASE",
                    "description", "hotel stay",
                    "requestedAmount", 89.00,
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andExpect(jsonPath("$.budgetSnapshot.availableAmount", closeTo(111.00, 0.001)))
            .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(authResp).get("eventId").asText();

        // Confirm $87.50 (partial — $1.50 less than requested)
        // Full reservation ($89.00) is released; only $87.50 is debited.
        // Expected post-confirm state:
        //   amountReserved = 0.00
        //   amountSpent    = 87.50
        //   availableAmount = 200.00 - 87.50 = 112.50
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "confirmedAmount", 87.50))))
            .andExpect(status().isOk());

        // Assert budget state after partial confirmation
        mockMvc.perform(get("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amountSpent",    closeTo(87.50,  0.001)))
            .andExpect(jsonPath("$.amountReserved", closeTo(0.00,   0.001)))
            .andExpect(jsonPath("$.availableAmount", closeTo(112.50, 0.001)));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
