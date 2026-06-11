package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.repository.SpendEventRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code reserve=false} creates a tree-root / coordinator marker: the AUTHORIZED event is
 * written and can anchor a chain, but it holds no capacity and has no confirmation timeout.
 * This is the structural fix for the orchestrator-root footgun — a root that neither
 * self-denies sub-agents nor gets swept mid-run.
 */
class ReserveFalseIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired SpendEventRepository spendEventRepository;

    @Test
    void reserveFalse_holdsNoCapacity_soSubAgentsDrawTheFullBudget() throws Exception {
        String sessionToken = createBudget(100.00);

        // Orchestrator opens the chain root WITHOUT holding any capacity.
        mockMvc.perform(authorize(sessionToken, "orchestrator", 100.00, false))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // Nothing is reserved — the whole budget is still available.
        mockMvc.perform(get("/api/v1/budgets/{id}", budgetIdFromToken(sessionToken))
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(jsonPath("$.quantityReserved", closeTo(0.00, 0.001)))
            .andExpect(jsonPath("$.availableQuantity", closeTo(100.00, 0.001)));

        // A sub-agent can draw the FULL budget — no self-denial.
        mockMvc.perform(authorize(sessionToken, "sub-agent", 100.00, true))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    @Test
    void reserveFalse_setsNoConfirmationTimeout_andIsStillConfirmable() throws Exception {
        String sessionToken = createBudget(50.00);

        String resp = mockMvc.perform(authorize(sessionToken, "orchestrator", 50.00, false))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(resp).get("eventId").asText();

        // No confirmation timeout — the sweep can never auto-void this root.
        SpendEvent event = spendEventRepository.findById(UUID.fromString(eventId)).orElseThrow();
        assertThat(event.getConfirmationTimeoutAt()).isNull();

        // Still confirmable — the coordinator's own actual consumption counts as spent.
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 5.00))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/budgets/{id}", budgetIdFromToken(sessionToken))
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(jsonPath("$.quantitySpent", closeTo(5.00, 0.001)));
    }

    // --- helpers --------------------------------------------------------------

    private String createBudget(double totalLimit) throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_reserve_false",
                    "intentContext", "reserve false test",
                    "totalLimit", totalLimit,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(createResp);
        // stash budgetId on the token map via a thread-local-free trick: re-read per call below
        lastBudgetId = json.get("id").asText();
        return json.get("tokens").get(0).get("sessionToken").asText();
    }

    private String lastBudgetId;

    private String budgetIdFromToken(String sessionToken) {
        return lastBudgetId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorize(
            String sessionToken, String agentId, double qty, boolean reserve) throws Exception {
        return post("/api/v1/authorize")
            .header("X-Session-Token", sessionToken)
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "agentId", agentId,
                "actionType", "task",
                "description", agentId + " action",
                "requestedQuantity", qty,
                "reserve", reserve,
                "idempotencyKey", UUID.randomUUID().toString())));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
