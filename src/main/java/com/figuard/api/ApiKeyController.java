package com.figuard.api;

import com.figuard.api.dto.request.CreateApiKeyRequest;
import com.figuard.api.dto.response.ApiKeyResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "API Keys", description = "Manage API keys for this tenant. Raw key values (`fg_live_` prefix) are returned only at creation or rotation time — subsequent reads return only the prefix and metadata.")
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "List API keys", description = "List all API keys for this tenant. Raw key values are never returned here.")
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        return ResponseEntity.ok(apiKeyService.listKeys(TenantContext.get()));
    }

    @Operation(
        summary = "Create an API key",
        description = "Create a new API key. The raw key (`fg_live_...`) is returned once in this response — store it securely. Subsequent reads return only the prefix and metadata."
    )
    @ApiResponse(responseCode = "201", description = "API key created")
    @PostMapping
    public ResponseEntity<ApiKeyResponse> createKey(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyResponse response = apiKeyService.createKey(request, TenantContext.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
        summary = "Revoke an API key",
        description = "Revoke a key immediately. Idempotent. The key record is retained for audit purposes — it is marked inactive, not deleted. Any in-flight requests using this key will be rejected."
    )
    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiKeyResponse> revokeKey(@PathVariable UUID id) {
        return ResponseEntity.ok(apiKeyService.revokeKey(id, TenantContext.get()));
    }

    @Operation(
        summary = "Rotate an API key",
        description = "Revoke the current key and issue a replacement atomically. The new raw key appears once in the response — store it before the request is gone. Use this for key rotation without a gap in service."
    )
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiKeyResponse> rotateKey(@PathVariable UUID id) {
        return ResponseEntity.ok(apiKeyService.rotateKey(id, TenantContext.get()));
    }
}
