package com.figuard.service;

import com.figuard.api.dto.request.CreateApiKeyRequest;
import com.figuard.api.dto.response.ApiKeyResponse;
import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 24;   // 192 bits → 32-char Base64url without padding
    private static final String KEY_PREFIX_LABEL = "fg_live_";

    private final ApiKeyRepository apiKeyRepository;

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys(Tenant tenant) {
        return apiKeyRepository.findByTenantOrderByCreatedAtDesc(tenant)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public ApiKeyResponse createKey(CreateApiKeyRequest request, Tenant tenant) {
        String rawKey = generateRawKey();
        ApiKey key = new ApiKey();
        key.setTenant(tenant);
        key.setKeyHash(HashUtil.sha256(rawKey));
        key.setKeyPrefix(rawKey.substring(0, Math.min(8, rawKey.length())));
        key.setDescription(request.getDescription());
        key.setActive(true);
        ApiKey saved = apiKeyRepository.saveAndFlush(key);

        log.info("API key created: prefix={} tenant={}", saved.getKeyPrefix(), tenant.getId());
        return toResponse(saved, rawKey);
    }

    /**
     * Revoke a key. Idempotent — already-inactive keys return 200 without error.
     * Does not delete the row; keeps audit history (lastUsedAt, createdAt).
     */
    @Transactional
    public ApiKeyResponse revokeKey(UUID keyId, Tenant tenant) {
        ApiKey key = apiKeyRepository.findByIdAndTenant(keyId, tenant)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "API key not found: " + keyId));

        key.setActive(false);
        ApiKey saved = apiKeyRepository.saveAndFlush(key);
        log.info("API key revoked: id={} tenant={}", keyId, tenant.getId());
        return toResponse(saved);
    }

    /**
     * Rotate a key: revoke the existing one and issue a new raw key atomically.
     * The new raw key is returned once in the response. The old key stops working immediately.
     */
    @Transactional
    public ApiKeyResponse rotateKey(UUID keyId, Tenant tenant) {
        ApiKey old = apiKeyRepository.findByIdAndTenant(keyId, tenant)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "API key not found: " + keyId));

        if (!old.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "API key is already revoked and cannot be rotated");
        }

        // Revoke old
        old.setActive(false);
        apiKeyRepository.save(old);

        // Issue replacement
        String rawKey = generateRawKey();
        ApiKey newKey = new ApiKey();
        newKey.setTenant(tenant);
        newKey.setKeyHash(HashUtil.sha256(rawKey));
        newKey.setKeyPrefix(rawKey.substring(0, Math.min(8, rawKey.length())));
        newKey.setDescription(old.getDescription());
        newKey.setActive(true);
        ApiKey savedNew = apiKeyRepository.saveAndFlush(newKey);

        log.info("API key rotated: oldId={} newPrefix={} tenant={}", keyId, savedNew.getKeyPrefix(), tenant.getId());
        return toResponse(savedNew, rawKey);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String generateRawKey() {
        byte[] bytes = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return KEY_PREFIX_LABEL + token;
    }

    private ApiKeyResponse toResponse(ApiKey key) {
        return toResponse(key, null);
    }

    private ApiKeyResponse toResponse(ApiKey key, String rawKey) {
        return ApiKeyResponse.builder()
            .id(key.getId())
            .keyPrefix(key.getKeyPrefix())
            .description(key.getDescription())
            .active(key.isActive())
            .createdAt(key.getCreatedAt())
            .lastUsedAt(key.getLastUsedAt())
            .rawKey(rawKey)
            .build();
    }
}
