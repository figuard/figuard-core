package com.figuard.conformance;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import com.figuard.domain.repository.TenantRepository;
import com.figuard.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-mode side of the drift gate: boots the REAL FiGuard server on a live port (Tomcat +
 * Testcontainers Postgres), seeds an API key, then runs the SAME conformance scenarios through
 * the figuard-lite {@code FiGuard} client in SERVER mode (a Python subprocess) and asserts they
 * pass. This proves the resolution layer's server path (the client's HTTP mapping) is conformant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerConformanceIT {

    static final PostgreSQLContainer<?> POSTGRES;
    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("figuard_server_conf")
            .withUsername("figuard")
            .withPassword("secret");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String API_KEY = "fg_live_serverconf";

    @LocalServerPort int port;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void seedApiKey() {
        String keyHash = HashUtil.sha256(API_KEY);
        if (apiKeyRepository.findByKeyHash(keyHash).isPresent()) return;
        Tenant tenant = new Tenant();
        tenant.setName("Server Conformance Tenant");
        tenant = tenantRepository.save(tenant);
        ApiKey apiKey = new ApiKey();
        apiKey.setTenant(tenant);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(API_KEY.substring(0, 8));
        apiKey.setDescription("server conformance key");
        apiKey.setActive(true);
        apiKeyRepository.save(apiKey);
    }

    @Test
    void figuardClientServerMode_matchesTheContract() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "python3", "lite/conformance/run_server.py",
            "--url", "http://localhost:" + port,
            "--api-key", API_KEY);
        pb.directory(new File(System.getProperty("user.dir")));   // = spentinel-core
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();

        System.out.println("---- run_server.py output ----\n" + output + "------------------------------");
        assertThat(exit).withFailMessage("server-mode conformance failed:\n%s", output).isZero();
    }
}
