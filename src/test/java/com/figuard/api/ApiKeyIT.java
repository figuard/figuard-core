package com.figuard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiKeyIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Test
    void createKey_returns201_with_rawKey_and_prefix() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("description", "CI pipeline"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.rawKey").isNotEmpty())
            .andExpect(jsonPath("$.rawKey", startsWith("ab_")))
            .andExpect(jsonPath("$.keyPrefix").isNotEmpty())
            .andExpect(jsonPath("$.description").value("CI pipeline"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void createKey_works_without_description() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.rawKey").isNotEmpty());
    }

    @Test
    void createKey_each_rawKey_is_unique() throws Exception {
        String r1 = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String r2 = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String key1 = objectMapper.readTree(r1).get("rawKey").asText();
        String key2 = objectMapper.readTree(r2).get("rawKey").asText();
        org.assertj.core.api.Assertions.assertThat(key1).isNotEqualTo(key2);
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    @Test
    void listKeys_returns_created_key_without_rawKey() throws Exception {
        // Create a key first
        String createResponse = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("description", "list test key"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String keyId = objectMapper.readTree(createResponse).get("id").asText();

        // List must include it, with no rawKey
        mockMvc.perform(get("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(keyId)))
            .andExpect(jsonPath("$[*].rawKey").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Revoke
    // -------------------------------------------------------------------------

    @Test
    void revokeKey_marks_key_inactive() throws Exception {
        String keyId = createKeyId();

        mockMvc.perform(post("/api/v1/api-keys/{id}/revoke", keyId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.rawKey").doesNotExist());
    }

    @Test
    void revokedKey_is_rejected_for_api_calls() throws Exception {
        // Create a key, get its raw value, revoke it, then try to use it
        String createResponse = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(createResponse);
        String keyId = json.get("id").asText();
        String rawKey = json.get("rawKey").asText();

        // Revoke it
        mockMvc.perform(post("/api/v1/api-keys/{id}/revoke", keyId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        // Using the revoked key must return 401
        mockMvc.perform(get("/api/v1/budgets")
                .header("X-Agent-Budget-Key", rawKey))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeKey_returns404_for_unknown_id() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys/{id}/revoke",
                java.util.UUID.randomUUID())
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Rotate
    // -------------------------------------------------------------------------

    @Test
    void rotateKey_returns_new_rawKey_and_revokes_old() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("description", "rotate test"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        var createJson = objectMapper.readTree(createResponse);
        String keyId = createJson.get("id").asText();
        String oldRawKey = createJson.get("rawKey").asText();

        String rotateResponse = mockMvc.perform(post("/api/v1/api-keys/{id}/rotate", keyId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rawKey").isNotEmpty())
            .andExpect(jsonPath("$.active").value(true))
            .andReturn().getResponse().getContentAsString();

        String newRawKey = objectMapper.readTree(rotateResponse).get("rawKey").asText();
        org.assertj.core.api.Assertions.assertThat(newRawKey).isNotEqualTo(oldRawKey);

        // Old key must now be rejected
        mockMvc.perform(get("/api/v1/budgets")
                .header("X-Agent-Budget-Key", oldRawKey))
            .andExpect(status().isUnauthorized());

        // New key must work
        mockMvc.perform(get("/api/v1/budgets")
                .header("X-Agent-Budget-Key", newRawKey))
            .andExpect(status().isOk());
    }

    @Test
    void rotateKey_returns409_for_already_revoked_key() throws Exception {
        String keyId = createKeyId();

        mockMvc.perform(post("/api/v1/api-keys/{id}/revoke", keyId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/api-keys/{id}/rotate", keyId)
                .header("X-Agent-Budget-Key", TEST_API_KEY))
            .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createKeyId() throws Exception {
        String response = mockMvc.perform(post("/api/v1/api-keys")
                .header("X-Agent-Budget-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }
}
