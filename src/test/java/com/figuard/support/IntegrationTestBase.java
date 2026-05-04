package com.figuard.support;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.domain.repository.TenantRepository;
import com.figuard.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    // Singleton container pattern: started once in a static initializer and never stopped
    // between test classes. This prevents the JUnit @Testcontainers extension from restarting
    // the container (with a new port) mid-suite, which would leave the cached Spring context
    // pointing at the old port and cause HikariPool total=0 for all subsequent IT classes.
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("agent_billing_test")
            .withUsername("agent_billing")
            .withPassword("secret")
            // Raise max_connections so concurrent tests (up to 50 threads) don't exhaust the DB
            .withCommand("postgres", "-c", "max_connections=200", "-c", "shared_buffers=256MB");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Concurrency tests fire up to 50 simultaneous threads — each needs its own connection
        // to hold a PESSIMISTIC_WRITE lock for the duration of the transaction.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "60");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "60000");
        registry.add("spring.datasource.hikari.keepalive-time", () -> "30000");
    }

    protected static final String TEST_API_KEY = "ab_live_integrationtest";

    @Autowired protected MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    protected Tenant tenant;

    @BeforeEach
    void seedApiKey() {
        String keyHash = HashUtil.sha256(TEST_API_KEY);
        var existing = apiKeyRepository.findByKeyHash(keyHash);
        if (existing.isPresent()) {
            tenant = existing.get().getTenant();
            return;
        }

        tenant = new Tenant();
        tenant.setName("Integration Test Tenant");
        tenant = tenantRepository.save(tenant);

        ApiKey apiKey = new ApiKey();
        apiKey.setTenant(tenant);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(TEST_API_KEY.substring(0, 8));
        apiKey.setDescription("Integration test key");
        apiKey.setActive(true);
        apiKeyRepository.save(apiKey);
    }
}
