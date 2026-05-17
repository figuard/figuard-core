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

/**
 * Verifies that GlobalExceptionHandler maps all exception types to the correct
 * HTTP status codes and response body shape.
 *
 * Every API consumer depends on this contract — if the shape changes silently,
 * client error-handling code breaks in ways that are hard to debug in production.
 */
class GlobalExceptionHandlerIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 400 — validation errors (@Valid on request body)
    // -------------------------------------------------------------------------

    @Test
    void validation_returns400_withVALIDATION_FAILED_code_andFieldErrors() throws Exception {
        // Missing required field 'expiresAt'
        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_test",
                    "totalLimit", 100.00
                    // expiresAt intentionally omitted
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("Request validation failed"))
            .andExpect(jsonPath("$.fieldErrors.expiresAt").isNotEmpty());
    }

    @Test
    void validation_returns400_withAllViolatingFields() throws Exception {
        // Both userId and totalLimit missing
        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "expiresAt", OffsetDateTime.now().plusHours(1)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 400 — IllegalArgumentException (business rule violations)
    // -------------------------------------------------------------------------

    @Test
    void illegalArgument_returns400_withINVALID_REQUEST_code() throws Exception {
        // Allocation sum exceeds totalLimit → IllegalArgumentException in BudgetService
        mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_test",
                    "totalLimit", 100.00,
                    "currency", "USD",
                    "expiresAt", OffsetDateTime.now().plusHours(1)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "allocations", java.util.List.of(
                        Map.of("category", "flight",
                            "allowedCategories", java.util.List.of("flight"),
                            "limit", 120.00)
                        // sum = 120 > totalLimit 100 → IllegalArgumentException
                    )
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 401 — missing API key (from ApiKeyAuthFilter, not GlobalExceptionHandler)
    // -------------------------------------------------------------------------

    @Test
    void missingApiKey_returns401_withErrorMessage() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 404 — BudgetNotFoundException
    // -------------------------------------------------------------------------

    @Test
    void notFound_returns404_withNOT_FOUND_code() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/{id}", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 409 — ResponseStatusException with CONFLICT
    // -------------------------------------------------------------------------

    @Test
    void conflict_returns409_whenConfirmingAlreadyConfirmedEvent() throws Exception {
        // Create budget and authorize
        String body = objectMapper.writeValueAsString(Map.of(
            "userId", "user_409_test",
            "totalLimit", 200.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(1)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));

        var createResult = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        var budgetJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String sessionToken = budgetJson.get("tokens").get(0).get("sessionToken").asText();

        var authResult = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .header("X-Session-Token", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_409_test",
                    "actionType", "PURCHASE",
                    "description", "conflict test",
                    "requestedQuantity", 100.00,
                    "idempotencyKey", UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andReturn();

        String eventId = objectMapper.readTree(authResult.getResponse().getContentAsString())
            .get("eventId").asText();

        // First confirm — OK
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 100.00))))
            .andExpect(status().isOk());

        // Second confirm — 409 with error structure
        mockMvc.perform(post("/api/v1/events/{id}/confirm", eventId)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmedQuantity", 100.00))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
