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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CurrencyMismatchIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private record Budget(String id, String sessionToken) {}

    @Test
    void authorize_returnsDenied_whenCurrencyDoesNotMatchBudget() throws Exception {
        // Budget is in USD
        Budget budget = createBudget("USD");

        // Request in EUR — should be denied
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("50.00", "EUR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("CURRENCY_MISMATCH"));
    }

    @Test
    void authorize_succeeds_whenCurrencyMatchesBudget() throws Exception {
        Budget budget = createBudget("USD");

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody("50.00", "USD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------

    private Budget createBudget(String currency) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_currency_test",
                    "intentContext", "travel spend",
                    "totalLimit", 500.00,
                    "currency", currency,
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("tokens").get(0).get("sessionToken").asText());
    }

    private String authorizeBody(String amount, String currency) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_001",
            "actionType", "PURCHASE",
            "description", "currency test",
            "requestedQuantity", Double.parseDouble(amount),
            "currency", currency,
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
