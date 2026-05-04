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

class SessionTokenIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void authorize_returns401_whenSessionTokenHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAuthorizeBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorize_returns401_whenSessionTokenIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", "st_totallyfaketoken")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAuthorizeBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorize_succeeds_withValidSessionToken() throws Exception {
        // Create a real budget and use its session token
        String createResponse = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_session_test",
                    "intentContext", "travel spend",
                    "totalLimit", 500.00,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String sessionToken = objectMapper.readTree(createResponse).get("sessionToken").asText();

        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAuthorizeBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));
    }

    // -------------------------------------------------------------------------

    private String validAuthorizeBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_session_test",
            "actionType", "PURCHASE",
            "description", "test purchase",
            "requestedAmount", 50.00,
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
