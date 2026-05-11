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

class SpendTreeIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Tree structure tests
    // -------------------------------------------------------------------------

    @Test
    void tree_returnsEmptyRoots_forBudgetWithNoEvents() throws Exception {
        Budget budget = createBudget(500.00);

        mockMvc.perform(get("/api/v1/budgets/{id}/tree", budget.id)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.budgetId").value(budget.id))
            .andExpect(jsonPath("$.roots").isArray())
            .andExpect(jsonPath("$.roots", hasSize(0)))
            .andExpect(jsonPath("$.totalEvents").value(0));
    }

    @Test
    void tree_showsOneRoot_withTwoChildren() throws Exception {
        Budget budget = createBudget(500.00);

        // Root event — no parent
        String rootEventId = authorize(budget.sessionToken, "100.00", null);

        // Two child events citing the root as parent
        String child1Id = authorize(budget.sessionToken, "50.00", rootEventId);
        String child2Id = authorize(budget.sessionToken, "75.00", rootEventId);

        String treeJson = mockMvc.perform(get("/api/v1/budgets/{id}/tree", budget.id)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalEvents").value(3))
            .andExpect(jsonPath("$.roots", hasSize(1)))
            .andExpect(jsonPath("$.roots[0].id").value(rootEventId))
            .andExpect(jsonPath("$.roots[0].children", hasSize(2)))
            .andReturn().getResponse().getContentAsString();

        // Verify both child IDs appear under the root
        JsonNode children = objectMapper.readTree(treeJson).get("roots").get(0).get("children");
        var childIds = java.util.stream.StreamSupport
            .stream(children.spliterator(), false)
            .map(n -> n.get("id").asText())
            .toList();
        assertThat(childIds).containsExactlyInAnyOrder(child1Id, child2Id);
    }

    @Test
    void tree_totalAuthorized_sumsBothRoots() throws Exception {
        Budget budget = createBudget(500.00);

        authorize(budget.sessionToken, "100.00", null);
        authorize(budget.sessionToken, "50.00", null); // second independent root

        mockMvc.perform(get("/api/v1/budgets/{id}/tree", budget.id)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roots", hasSize(2)))
            .andExpect(jsonPath("$.totalEvents").value(2))
            .andExpect(jsonPath("$.totalAuthorized").value(150.00));
    }

    @Test
    void tree_leafNode_hasNullChildren() throws Exception {
        Budget budget = createBudget(500.00);
        authorize(budget.sessionToken, "100.00", null);

        // A leaf has no children — NON_NULL omits the field entirely
        mockMvc.perform(get("/api/v1/budgets/{id}/tree", budget.id)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roots[0].children").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Ledger tests
    // -------------------------------------------------------------------------

    @Test
    void ledger_returnsAllEvents_newestFirst() throws Exception {
        Budget budget = createBudget(500.00);

        authorize(budget.sessionToken, "100.00", null);
        authorize(budget.sessionToken, "50.00", null);
        authorize(budget.sessionToken, "75.00", null);

        mockMvc.perform(get("/api/v1/budgets/{id}/ledger", budget.id)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content", hasSize(3)))
            .andExpect(jsonPath("$.content[0].decision").value("AUTHORIZED"));
    }

    @Test
    void ledger_filtersByDecision() throws Exception {
        Budget budget = createBudget(300.00);

        authorize(budget.sessionToken, "100.00", null); // AUTHORIZED
        authorizeExpectingDenial(budget.sessionToken, "999.00"); // DENIED: INSUFFICIENT_FUNDS

        mockMvc.perform(get("/api/v1/budgets/{id}/ledger", budget.id)
                .param("decision", "AUTHORIZED")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].decision").value("AUTHORIZED"));

        mockMvc.perform(get("/api/v1/budgets/{id}/ledger", budget.id)
                .param("decision", "DENIED")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].decision").value("DENIED"));
    }

    @Test
    void ledger_returns404_forUnknownBudget() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/{id}/ledger", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void tree_returns404_forUnknownBudget() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/{id}/tree", UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record Budget(String id, String sessionToken) {}

    private Budget createBudget(double totalLimit) throws Exception {
        Map<String, Object> body = Map.of(
            "userId", "user_tree_test",
            "intentContext", "travel spend",
            "totalLimit", totalLimit,
            "currency", "USD",
            "expiresAt", OffsetDateTime.now().plusHours(2)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("sessionToken").asText());
    }

    private String authorize(String sessionToken, String amount, String parentEventId) throws Exception {
        var bodyBuilder = new java.util.LinkedHashMap<String, Object>();
        bodyBuilder.put("agentId", "agent_tree_test");
        bodyBuilder.put("actionType", "PURCHASE");
        bodyBuilder.put("description", "tree test spend");
        bodyBuilder.put("requestedQuantity", Double.parseDouble(amount));
        bodyBuilder.put("currency", "USD");
        bodyBuilder.put("idempotencyKey", UUID.randomUUID().toString());
        if (parentEventId != null) {
            bodyBuilder.put("parentEventId", parentEventId);
        }

        String response = mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bodyBuilder)))
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"))
            .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("eventId").asText();
    }

    private void authorizeExpectingDenial(String sessionToken, String amount) throws Exception {
        var body = Map.of(
            "agentId", "agent_tree_test",
            "actionType", "PURCHASE",
            "description", "tree test spend",
            "requestedQuantity", Double.parseDouble(amount),
            "currency", "USD",
            "idempotencyKey", UUID.randomUUID().toString()
        );

        mockMvc.perform(post("/api/v1/authorize")
            .header("X-Session-Token", sessionToken)
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }
}
