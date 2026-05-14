package com.figuard.api;

import com.figuard.api.dto.request.CreateApiKeyRequest;
import com.figuard.api.dto.response.ApiKeyResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /** List all API keys for this tenant. Raw key values are never returned here. */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        return ResponseEntity.ok(apiKeyService.listKeys(TenantContext.get()));
    }

    /**
     * Create a new API key. The raw key is returned once in the response body — store it.
     * Subsequent reads return only the prefix and metadata.
     */
    @PostMapping
    public ResponseEntity<ApiKeyResponse> createKey(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyResponse response = apiKeyService.createKey(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Revoke a key. Idempotent. The row is retained for audit purposes — just marked inactive.
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiKeyResponse> revokeKey(@PathVariable UUID id) {
        return ResponseEntity.ok(apiKeyService.revokeKey(id, TenantContext.get()));
    }

    /**
     * Rotate a key: revoke the current one and issue a new one atomically.
     * The new raw key appears once in the response — store it before the request is gone.
     */
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiKeyResponse> rotateKey(@PathVariable UUID id) {
        return ResponseEntity.ok(apiKeyService.rotateKey(id, TenantContext.get()));
    }
}
