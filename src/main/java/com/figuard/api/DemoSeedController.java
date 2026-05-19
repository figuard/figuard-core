package com.figuard.api;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.domain.repository.TenantRepository;
import com.figuard.util.HashUtil;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * One-shot endpoint to seed a demo tenant + API key so the demo script can run
 * against a fresh Docker stack without any manual DB setup.
 *
 * Enabled in all profiles except "prod" — excluded from production via @Profile.
 * Idempotent: calling it multiple times with the same key is safe.
 */
@Hidden
@RestController
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
@Profile("!prod")
public class DemoSeedController {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;

    @PostMapping("/seed")
    @Transactional
    public ResponseEntity<Map<String, String>> seed(@RequestBody Map<String, String> body) {
        String rawKey = body.getOrDefault("apiKey", "ab_live_integrationtest");
        String keyHash = HashUtil.sha256(rawKey);

        if (apiKeyRepository.findByKeyHash(keyHash).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "already_exists"));
        }

        Tenant tenant = new Tenant();
        tenant.setName("Demo Tenant");
        tenant = tenantRepository.save(tenant);

        ApiKey apiKey = new ApiKey();
        apiKey.setTenant(tenant);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(rawKey.substring(0, Math.min(8, rawKey.length())));
        apiKey.setDescription("Demo API key — seeded by /internal/demo/seed");
        apiKey.setActive(true);
        apiKeyRepository.save(apiKey);

        return ResponseEntity.ok(Map.of("status", "seeded", "tenantId", tenant.getId().toString()));
    }
}
