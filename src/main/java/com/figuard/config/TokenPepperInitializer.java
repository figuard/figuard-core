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

    // Accept either the property figuard.security.token-pepper or the FIGUARD_TOKEN_PEPPER
    // env var directly (the name documented in README/spec/compose), falling back to empty.
    @Value("${figuard.security.token-pepper:${FIGUARD_TOKEN_PEPPER:}}")
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
