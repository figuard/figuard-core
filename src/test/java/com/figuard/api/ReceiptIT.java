package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReceiptIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------

    @Test
    void receipt_generatesUrl_afterConfirmedSpend() throws Exception {
        Budget budget = createBudget(500.00);
        String eventId = authorize(budget, "49.99");
        confirmEvent(eventId, "49.99");

        mockMvc.perform(get("/api/v1/budgets/{id}/receipt", budget.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.receiptUrl").isNotEmpty())
            .andExpect(jsonPath("$.receiptUrl", containsString("/receipts/")));
    }

    @Test
    void receipt_isIdempotent_secondCallReturnsSameUrl() throws Exception {
        Budget budget = createBudget(500.00);
        String eventId = authorize(budget, "20.00");
        confirmEvent(eventId, "20.00");

        String url1 = extractReceiptUrl(budget.id());
        String url2 = extractReceiptUrl(budget.id());

        // Same token every time — no new receipt row created on second call
        assertThat(url1).isEqualTo(url2);

    }

    @Test
    void receipt_rendersPage_withConfirmedSpend() throws Exception {
        Budget budget = createBudget(300.00);
        String eventId = authorize(budget, "99.00");
        confirmEvent(eventId, "97.50");

        String receiptUrl = extractReceiptUrl(budget.id());
        String token = receiptUrl.substring(receiptUrl.lastIndexOf('/') + 1);

        mockMvc.perform(get("/receipts/{token}", token))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("Spend Receipt")))
            .andExpect(content().string(containsString("97.50")))
            .andExpect(content().string(containsString(budget.id())));
    }

    @Test
    void receipt_rendersPage_withNoEvents_whenNoneConfirmed() throws Exception {
        Budget budget = createBudget(200.00);
        // Authorize but don't confirm — receipt should render, but show no transactions
        authorize(budget, "50.00");

        String token = extractToken(budget.id());

        mockMvc.perform(get("/receipts/{token}", token))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No confirmed transactions yet")));
    }

    @Test
    void receipt_returns404_forUnknownToken() throws Exception {
        mockMvc.perform(get("/receipts/{token}", "unknowntoken00000000000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("Receipt Not Found")));
    }

    @Test
    void receipt_publicEndpoint_requiresNoApiKey() throws Exception {
        Budget budget = createBudget(100.00);
        String token = extractToken(budget.id());

        // No X-Agent-Budget-Key header — should still return 200
        mockMvc.perform(get("/receipts/{token}", token))
            .andExpect(status().isOk());
    }

    @Test
    void receipt_authenticatedEndpoint_requires401_withoutApiKey() throws Exception {
        Budget budget = createBudget(100.00);

        mockMvc.perform(get("/api/v1/budgets/{id}/receipt", budget.id()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void receipt_returns404_forUnknownBudget() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/{id}/receipt", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record Budget(String id, String sessionToken) {}

    private Budget createBudget(double totalLimit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "userId", "user_receipt_test",
                        "intentContext", "receipt integration test",
                        "totalLimit", totalLimit,
                        "currency", "USD",
                        "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("tokens").get(0).get("sessionToken").asText());
    }

    private String authorize(Budget budget, String amount) throws Exception {
        String response = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", budget.sessionToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "agentId", "agent_receipt_test",
                        "actionType", "PURCHASE",
                        "description", "Receipt test purchase",
                        "requestedQuantity", Double.parseDouble(amount),
                        "currency", "USD",
                        "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("eventId").asText();
    }

    private void confirmEvent(String eventId, String confirmedAmount) throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "confirmedQuantity", Double.parseDouble(confirmedAmount)))))
            .andExpect(status().isOk());
    }

    private String extractReceiptUrl(String budgetId) throws Exception {
        String response = mockMvc.perform(get("/api/v1/budgets/{id}/receipt", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("receiptUrl").asText();
    }

    private String extractToken(String budgetId) throws Exception {
        String url = extractReceiptUrl(budgetId);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

}
