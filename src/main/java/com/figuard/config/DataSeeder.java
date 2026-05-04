package com.figuard.config;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.domain.repository.TenantRepository;
import com.figuard.security.ApiKeyAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    static final String RAW_KEY = "ab_live_demo";

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;

    @Override
    public void run(String... args) {
        if (apiKeyRepository.findByKeyHash(ApiKeyAuthFilter.sha256(RAW_KEY)).isPresent()) {
            log.info("[DataSeeder] Seed data already present — skipping");
            return;
        }

        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant = tenantRepository.save(tenant);

        ApiKey apiKey = new ApiKey();
        apiKey.setTenant(tenant);
        apiKey.setKeyHash(ApiKeyAuthFilter.sha256(RAW_KEY));
        apiKey.setKeyPrefix(RAW_KEY.substring(0, 8));
        apiKey.setDescription("Demo API key");
        apiKey.setActive(true);
        apiKeyRepository.save(apiKey);

        log.info("========================================");
        log.info("[DataSeeder] Test tenant created: id={}", tenant.getId());
        log.info("[DataSeeder] API key ready — use this in Postman/curl:");
        log.info("[DataSeeder]   X-Agent-Budget-Key: {}", RAW_KEY);
        log.info("========================================");
    }
}
