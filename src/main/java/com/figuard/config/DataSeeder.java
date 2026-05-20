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

    static final String RAW_KEY = "fg_live_demo";

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

        log.info("[DataSeeder] Seed tenant created: id={}", tenant.getId());

        System.out.println();
        System.out.println("================================================");
        System.out.println("  FiGuard is ready");
        System.out.println();
        System.out.println("  API:       http://localhost:8080");
        System.out.println("  Demo key:  " + RAW_KEY);
        System.out.println();
        System.out.println("  Try it:");
        System.out.println("  curl -H \"X-Agent-Budget-Key: " + RAW_KEY + "\" \\");
        System.out.println("       http://localhost:8080/api/v1/budgets");
        System.out.println("================================================");
        System.out.println();
    }
}
