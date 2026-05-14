package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetControllerIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------

    @Test
    void createBudget_returns201_withSessionToken() throws Exception {
        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBudgetJson(400.00)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.sessionToken").isNotEmpty())
            .andExpect(jsonPath("$.sessionToken", startsWith("st_")))
            .andExpect(jsonPath("$.sessionTokenPrefix").isNotEmpty())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.availableQuantity").value(400.00));
    }

    @Test
    void createBudget_returns400_withoutExpiresAt() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_test",
            "totalLimit", 100.00
            // expiresAt intentionally omitted
        );

        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.expiresAt").isNotEmpty());
    }

    @Test
    void createBudget_returns400_whenAllocationSumExceedsLimit() throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_test",
            "totalLimit", 100.00,
            "currency", "USD",
            "expiresAt", expiresAt(),
            "allocations", List.of(
                Map.of("category", "flight",
                    "allowedCategories", List.of("flight"),
                    "limit", 80.00),
                Map.of("category", "hotel",
                    "allowedCategories", List.of("hotel"),
                    "limit", 60.00)   // sum = 140 > totalLimit 100
            )
        );

        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message", containsString("Allocation limits sum")));
    }

    @Test
    void getBudget_returns404_forUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/{id}", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void createBudget_sessionTokenInResponse_notInSubsequentGet() throws Exception {
        // Step 1 — create and capture sessionToken
        String createResponse = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBudgetJson(200.00)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionToken").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        String budgetId = objectMapper.readTree(createResponse).get("id").asText();

        // Step 2 — GET the same budget, sessionToken must be absent (null)
        mockMvc.perform(get("/api/v1/budgets/{id}", budgetId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(budgetId))
            .andExpect(jsonPath("$.sessionToken").doesNotExist());
    }

    // -------------------------------------------------------------------------

    private String validBudgetJson(double totalLimit) throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_alice_123",
            "intentContext", "Book travel to NYC",
            "totalLimit", totalLimit,
            "currency", "USD",
            "expiresAt", expiresAt()
        );
        return objectMapper.writeValueAsString(body);
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
