package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.entity.WebhookConfig;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import com.figuard.domain.repository.WebhookConfigRepository;
import com.figuard.domain.repository.WebhookDeliveryRepository;
import com.figuard.service.WebhookDispatcher;
import com.figuard.support.IntegrationTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebhookIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired WebhookConfigRepository webhookConfigRepository;
    @Autowired WebhookDeliveryRepository deliveryRepository;
    @Autowired AgentBudgetRepository budgetRepository;
    @Autowired BudgetAnomalyBaselineRepository baselineRepository;

    private WireMockServer wireMock;

    private record Budget(String id, String sessionToken) {}

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // Default: accept all POSTs
        wireMock.stubFor(WireMock.post(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
        // Delete deliveries first — they hold FK references to configs
        deliveryRepository.deleteAll();
        webhookConfigRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Threshold tests
    // -------------------------------------------------------------------------

    @Test
    void webhook_fires_whenBudgetCrosses90PercentThreshold() throws Exception {
        registerWebhookConfig("BUDGET_90_PCT");

        // $100 budget — authorize $91 → 91% used, crosses 90% threshold
        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "91.00");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withRequestBody(containing("\"eventType\":\"BUDGET_90_PCT\"")))
        );
    }

    @Test
    void webhook_fires_whenBudgetCrosses50PercentThreshold() throws Exception {
        registerWebhookConfig("BUDGET_50_PCT");

        // $100 budget — authorize $51 → 51% used, crosses 50% threshold
        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "51.00");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withRequestBody(containing("\"eventType\":\"BUDGET_50_PCT\"")))
        );
    }

    @Test
    void webhook_fires_withCorrectHmacSignature() throws Exception {
        String secret = "my-test-secret";
        registerWebhookConfigWithSecret("BUDGET_90_PCT", secret);

        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "91.00");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("X-Webhook-Signature", matching("sha256=[a-f0-9]{64}")))
        );

        // Verify the HMAC value matches what we compute independently
        var serveEvents = wireMock.getAllServeEvents();
        assertThat(serveEvents).isNotEmpty();

        var captured = serveEvents.get(0);
        String receivedBody = captured.getRequest().getBodyAsString();
        String receivedSig  = captured.getRequest().getHeader("X-Webhook-Signature");

        String expectedHex = WebhookDispatcher.hmacSha256(receivedBody, secret);
        assertThat(receivedSig).isEqualTo("sha256=" + expectedHex);
    }

    @Test
    void webhook_payload_hasRequiredFields() throws Exception {
        registerWebhookConfig("BUDGET_90_PCT");
        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "91.00");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(wireMock.getAllServeEvents()).isNotEmpty()
        );

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("eventType").asText()).isEqualTo("BUDGET_90_PCT");
        assertThat(json.get("budgetId").asText()).isEqualTo(budget.id());
        assertThat(json.has("totalLimit")).isTrue();
        assertThat(json.has("availableQuantity")).isTrue();
        assertThat(json.has("percentUsed")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
    }

    @Test
    void webhook_fires_spendDenied_whenAuthorizationDenied() throws Exception {
        registerWebhookConfig("SPEND_DENIED");
        Budget budget = createBudget(100.00);

        // Request more than available — will be denied INSUFFICIENT_FUNDS
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_001",
                    "actionType", "PURCHASE",
                    "description", "over limit",
                    "requestedQuantity", 999.00,
                    "currency", "USD",
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withRequestBody(containing("\"eventType\":\"SPEND_DENIED\"")))
        );
    }

    @Test
    void webhook_retries_onNon2xxResponse() throws Exception {
        // Reset stubs and configure a scenario: fail first, succeed second
        wireMock.resetAll();
        wireMock.stubFor(WireMock.post(anyUrl())
            .inScenario("retry-test")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("first-failed"));
        wireMock.stubFor(WireMock.post(anyUrl())
            .inScenario("retry-test")
            .whenScenarioStateIs("first-failed")
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        registerWebhookConfig("BUDGET_90_PCT");
        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "91.00");

        // First attempt returns 500, then 1s sleep, second attempt returns 200
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            wireMock.verify(2, postRequestedFor(urlEqualTo("/webhook")))
        );
    }

    @Test
    void webhook_recordsDelivery_inDatabase() throws Exception {
        registerWebhookConfig("BUDGET_90_PCT");
        Budget budget = createBudget(100.00);
        doAuthorize(budget.sessionToken(), "91.00");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            var deliveries = deliveryRepository.findAll();
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).getEventType()).isEqualTo("BUDGET_90_PCT");
            assertThat(deliveries.get(0).getStatus().name()).isEqualTo("DELIVERED");
            assertThat(deliveries.get(0).getResponseStatus()).isEqualTo(200);
            assertThat(deliveries.get(0).getAttemptCount()).isEqualTo(1);
        });
    }

    @Test
    void webhook_fires_anomalyDetected_andBudgetPaused_onAutoPause() throws Exception {
        // Register for both event types that fire on auto-pause anomaly
        WebhookConfig anomalyConfig = new WebhookConfig();
        anomalyConfig.setTenant(tenant);
        anomalyConfig.setUrl("http://localhost:" + wireMock.port() + "/webhook");
        anomalyConfig.setSecret("test-webhook-secret");
        anomalyConfig.setActive(true);
        anomalyConfig.setEvents(new String[]{"ANOMALY_DETECTED", "BUDGET_PAUSED"});
        webhookConfigRepository.save(anomalyConfig);

        // Create a budget with anomaly detection + auto-pause enabled
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId",                    "user_anomaly_webhook_test",
                    "intentContext",             "anomaly webhook test",
                    "totalLimit",                2000.00,
                    "currency",                  "USD",
                    "anomalyDetectionEnabled",   true,
                    "autoPauseOnAnomaly",        true,
                    "expiresAt",                 expiresAt()
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(response);
        String sessionToken = json.get("tokens").get(0).get("sessionToken").asText();
        UUID budgetId = UUID.fromString(json.get("id").asText());

        // Seed baseline directly — AnomalyBaselineService updates only on CONFIRMED events
        // (async), so we inject the row the same way AnomalyDetectionIT does.
        AgentBudget budget = budgetRepository.findById(budgetId).orElseThrow();
        BudgetAnomalyBaseline baseline = new BudgetAnomalyBaseline();
        baseline.setBudget(budget);
        baseline.setTenant(tenant);
        baseline.setSampleCount(5);
        baseline.setMeanAmount(new java.math.BigDecimal("10.00"));
        baseline.setMinAmount(new java.math.BigDecimal("10.00"));
        baseline.setMaxAmount(new java.math.BigDecimal("10.00"));
        baselineRepository.save(baseline);

        // Fire a request that is anomalously large vs the $10 baseline
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId",           "anomaly_agent",
                    "actionType",        "PURCHASE",
                    "description",       "anomalously large purchase",
                    "requestedQuantity", 500.00,
                    "currency",          "USD",
                    "idempotencyKey",    UUID.randomUUID().toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DENIED"))
            .andExpect(jsonPath("$.denialReason").value("ANOMALY_DETECTED"));

        // Both BUDGET_PAUSED and ANOMALY_DETECTED webhooks must have fired
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withRequestBody(containing("\"eventType\":\"BUDGET_PAUSED\"")));
            wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withRequestBody(containing("\"eventType\":\"ANOMALY_DETECTED\"")));
        });
    }

    @Test
    void webhook_doesNotFire_whenConfigSubscribesToDifferentEvent() throws Exception {
        // Config only subscribes to BUDGET_90_PCT — SPEND_DENIED should NOT trigger it
        registerWebhookConfig("BUDGET_90_PCT");
        Budget budget = createBudget(100.00);

        // Cause a denial (not a threshold crossing)
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", budget.sessionToken())
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_001",
                    "actionType", "PURCHASE",
                    "description", "test",
                    "requestedQuantity", 5.00,   // small amount, no threshold crossed
                    "currency", "USD",
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("AUTHORIZED"));

        // Brief wait to confirm no spurious webhook fired
        Thread.sleep(500);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/webhook")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void registerWebhookConfig(String eventType) {
        registerWebhookConfigWithSecret(eventType, "test-webhook-secret");
    }

    private void registerWebhookConfigWithSecret(String eventType, String secret) {
        WebhookConfig config = new WebhookConfig();
        config.setTenant(tenant);
        config.setUrl("http://localhost:" + wireMock.port() + "/webhook");
        config.setSecret(secret);
        config.setActive(true);
        config.setEvents(new String[]{eventType});
        webhookConfigRepository.save(config);
    }

    private Budget createBudget(double totalLimit) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", "user_webhook_test",
                    "intentContext", "webhook test spend",
                    "totalLimit", totalLimit,
                    "currency", "USD",
                    "expiresAt", expiresAt()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        return new Budget(json.get("id").asText(), json.get("tokens").get(0).get("sessionToken").asText());
    }

    private void doAuthorize(String sessionToken, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/authorize")
                .header("X-Session-Token", sessionToken)
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "agentId", "agent_webhook_test",
                    "actionType", "PURCHASE",
                    "description", "webhook test",
                    "requestedQuantity", Double.parseDouble(amount),
                    "currency", "USD",
                    "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(status().isOk());
    }

    private static String expiresAt() {
        return OffsetDateTime.now().plusHours(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
