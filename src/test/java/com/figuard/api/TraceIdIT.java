package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TraceIdIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    private static final Pattern UUID_PATTERN =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    // -------------------------------------------------------------------------
    // Every endpoint must return X-Trace-Id
    // -------------------------------------------------------------------------

    @Test
    void createBudget_respondsWithTraceId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(budgetJson()))
            .andExpect(status().isCreated())
            .andReturn();

        assertValidTraceId(result);
    }

    @Test
    void authorize_respondsWithTraceId() throws Exception {
        Budget budget = createBudget();

        MvcResult result = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeJson("50.00")))
            .andExpect(status().isOk())
            .andReturn();

        assertValidTraceId(result);
    }

    @Test
    void authorize_traceIdChangesEachRequest() throws Exception {
        Budget budget = createBudget();

        MvcResult r1 = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeJson("10.00")))
            .andExpect(status().isOk())
            .andReturn();

        MvcResult r2 = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeJson("10.00")))
            .andExpect(status().isOk())
            .andReturn();

        String traceId1 = r1.getResponse().getHeader("X-Trace-Id");
        String traceId2 = r2.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId1).isNotEqualTo(traceId2);
    }

    @Test
    void unauthorized_request_alsoReceivesTraceId() throws Exception {
        // Even 401 responses get a traceId — TraceIdFilter runs before auth
        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(budgetJson()))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertValidTraceId(result);
    }

    @Test
    void actuator_health_receivesTraceId() throws Exception {
        // Actuator bypasses auth but TraceIdFilter still runs
        MvcResult result = mockMvc.perform(get("/actuator/health"))
            .andReturn();

        assertValidTraceId(result);
    }

    // -------------------------------------------------------------------------
    // traceId field in response body (not just header)
    // -------------------------------------------------------------------------

    @Test
    void createBudget_responseBody_containsTraceId_matchingHeader() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(budgetJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String bodyTraceId = objectMapper.readTree(
            result.getResponse().getContentAsString()).get("traceId").asText();

        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    @Test
    void authorize_responseBody_containsTraceId_matchingHeader() throws Exception {
        Budget budget = createBudget();

        MvcResult result = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeJson("50.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String bodyTraceId = objectMapper.readTree(
            result.getResponse().getContentAsString()).get("traceId").asText();

        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    @Test
    void getBudget_responseBody_containsTraceId() throws Exception {
        Budget budget = createBudget();

        mockMvc.perform(get("/api/v1/budgets/{id}", budget.id())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // Token safety: session token and key hash must not appear in responses
    // -------------------------------------------------------------------------

    @Test
    void authorize_responseBody_doesNotContainSessionToken() throws Exception {
        Budget budget = createBudget();

        MvcResult result = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeJson("50.00")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(budget.sessionToken());
        assertThat(body).doesNotContain(TEST_API_KEY);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record Budget(String id, String sessionToken) {}

    private Budget createBudget() throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(budgetJson()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("tokens").get(0).get("sessionToken").asText());
    }

    private String budgetJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "userId", "user_trace_test",
            "intentContext", "trace id test",
            "totalLimit", 500.00,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));
    }

    private String authorizeJson(String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "agentId", "agent_trace_test",
            "actionType", "PURCHASE",
            "description", "trace test",
            "requestedQuantity", Double.parseDouble(amount),
            "idempotencyKey", UUID.randomUUID().toString()
        ));
    }

    private void assertValidTraceId(MvcResult result) {
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId)
            .as("X-Trace-Id header must be present")
            .isNotNull()
            .isNotBlank();
        assertThat(UUID_PATTERN.matcher(traceId).matches())
            .as("X-Trace-Id must be a valid UUID, got: " + traceId)
            .isTrue();
    }
}
