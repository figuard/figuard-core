package com.figuard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.WebhookConfig;
import com.figuard.domain.repository.WebhookConfigRepository;
import com.figuard.domain.repository.WebhookDeliveryRepository;
import com.figuard.support.IntegrationTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;  // qualified use: WireMock.post(anyUrl())
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
// Explicit MockMvc imports take priority over the WireMock wildcard above,
// preventing the compiler from resolving post()/get()/delete() to WireMock's MappingBuilder.
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookCrudIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired WebhookConfigRepository webhookConfigRepository;
    @Autowired WebhookDeliveryRepository deliveryRepository;

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(WireMock.post(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody("ok")));

    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
        deliveryRepository.deleteAll();
        webhookConfigRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/webhooks — create
    // -------------------------------------------------------------------------

    @Test
    void createWebhook_returns201_withValidRequest() throws Exception {
        String response = mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", webhookUrl(),
                    "secret", "my-signing-secret-abc123",
                    "events", List.of("BUDGET_90_PCT", "SPEND_DENIED")))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.url").value(webhookUrl()))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.events").isArray())
            .andReturn().getResponse().getContentAsString();

        // Secret must never appear in the response
        assertThat(response).doesNotContain("my-signing-secret-abc123");
    }

    @Test
    void createWebhook_returns400_whenUrlBlank() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", "",
                    "secret", "my-signing-secret-abc123",
                    "events", List.of("BUDGET_90_PCT")))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createWebhook_returns400_whenSecretTooShort() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", webhookUrl(),
                    "secret", "short",
                    "events", List.of("BUDGET_90_PCT")))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createWebhook_returns400_whenEventsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", webhookUrl(),
                    "secret", "my-signing-secret-abc123",
                    "events", List.of()))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createWebhook_returns400_whenInvalidEventType() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", webhookUrl(),
                    "secret", "my-signing-secret-abc123",
                    "events", List.of("NOT_A_REAL_EVENT")))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createWebhook_returns400_whenWebhookTestUsedAsSubscription() throws Exception {
        // WEBHOOK_TEST is internal-only, not subscribable
        mockMvc.perform(post("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "url", webhookUrl(),
                    "secret", "my-signing-secret-abc123",
                    "events", List.of("WEBHOOK_TEST")))))
            .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/webhooks — list
    // -------------------------------------------------------------------------

    @Test
    void listWebhooks_returnsAllConfigsForTenant() throws Exception {
        createConfigDirect("BUDGET_90_PCT");
        createConfigDirect("SPEND_DENIED");

        mockMvc.perform(get("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listWebhooks_returnsEmptyList_whenNoneRegistered() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/v1/webhooks/{id}
    // -------------------------------------------------------------------------

    @Test
    void deleteWebhook_returns204_andRemovesConfig() throws Exception {
        WebhookConfig config = createConfigDirect("BUDGET_90_PCT");

        mockMvc.perform(delete("/api/v1/webhooks/" + config.getId())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNoContent());

        assertThat(webhookConfigRepository.findById(config.getId())).isEmpty();
    }

    @Test
    void deleteWebhook_returns404_whenNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/webhooks/" + UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/webhooks/{id}/deliveries
    // -------------------------------------------------------------------------

    @Test
    void getDeliveries_returnsEmptyList_whenNoDeliveries() throws Exception {
        WebhookConfig config = createConfigDirect("BUDGET_90_PCT");

        mockMvc.perform(get("/api/v1/webhooks/" + config.getId() + "/deliveries")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getDeliveries_returns404_forUnknownConfig() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/" + UUID.randomUUID() + "/deliveries")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/webhooks/{id}/test
    // -------------------------------------------------------------------------

    @Test
    void testWebhook_returnsSuccess_whenEndpointResponds2xx() throws Exception {
        WebhookConfig config = createConfigDirect("BUDGET_90_PCT");

        String response = mockMvc.perform(
                post("/api/v1/webhooks/" + config.getId() + "/test")
                    .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.responseStatus").value(200))
            .andExpect(jsonPath("$.durationMs").isNumber())
            .andReturn().getResponse().getContentAsString();

        // Verify WireMock received a POST with correct event header
        wireMock.verify(1, postRequestedFor(anyUrl())
            .withHeader("X-Webhook-Event", equalTo("WEBHOOK_TEST")));

        // Test delivery should NOT create a delivery record
        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void testWebhook_returnsFailure_whenEndpointResponds5xx() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.post(anyUrl())
            .willReturn(aResponse().withStatus(500).withBody("error")));


        WebhookConfig config = createConfigDirect("BUDGET_90_PCT");

        mockMvc.perform(
                post("/api/v1/webhooks/" + config.getId() + "/test")
                    .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.responseStatus").value(500));
    }

    @Test
    void testWebhook_returns404_forUnknownConfig() throws Exception {
        mockMvc.perform(
                post("/api/v1/webhooks/" + UUID.randomUUID() + "/test")
                    .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WebhookConfig createConfigDirect(String eventType) {
        WebhookConfig config = new WebhookConfig();
        config.setTenant(tenant);
        config.setUrl(webhookUrl());
        config.setSecret("my-signing-secret-abc123");
        config.setActive(true);
        config.setEvents(new String[]{eventType});
        return webhookConfigRepository.save(config);
    }

    private String webhookUrl() {
        return "http://localhost:" + wireMock.port() + "/webhook";
    }
}
