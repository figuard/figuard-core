package com.figuard.service;

import com.figuard.util.HashUtil;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class SessionTokenService {

    private static final String PREFIX = "st_";
    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_RANDOM_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    // Generates a unique session token: "st_" + 32 cryptographically random alphanumeric chars
    public String generateToken() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + TOKEN_RANDOM_LENGTH);
        sb.append(PREFIX);
        for (int i = 0; i < TOKEN_RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // SHA-256 hex of the raw token — this is what gets stored in the DB
    public String hashToken(String rawToken) {
        return HashUtil.sha256(rawToken);
    }

    // First 12 chars for display/debugging — never the full token
    public String extractPrefix(String rawToken) {
        return rawToken.substring(0, 12);
    }
}
