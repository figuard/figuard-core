package com.figuard.config;

import com.figuard.util.HashUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads the credential-hashing pepper into {@link HashUtil} once at startup.
 *
 * Set {@code FIGUARD_TOKEN_PEPPER} (or {@code figuard.security.token-pepper}) to a long
 * random secret in production. When set, API keys and session tokens are stored as
 * HMAC-SHA256(token, pepper) instead of plain SHA-256 — a database breach without the
 * pepper yields hashes an attacker cannot verify offline.
 *
 * Generate one with: openssl rand -base64 48
 */
@Slf4j
@Component
public class TokenPepperInitializer {

    @Value("${figuard.security.token-pepper:}")
    private String pepper;

    @PostConstruct
    public void init() {
        HashUtil.setPepper(pepper);
        if (HashUtil.isPepperSet()) {
            log.info("Token pepper configured — credentials stored as HMAC-SHA256.");
        } else {
            log.warn("FIGUARD_TOKEN_PEPPER not set — credentials stored as plain SHA-256. "
                + "Set a pepper in production: openssl rand -base64 48");
        }
    }
}
