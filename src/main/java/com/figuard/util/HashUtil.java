package com.figuard.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

    private HashUtil() {}

    /**
     * Server-side pepper for credential hashing. Set once at startup from
     * {@code figuard.security.token-pepper} (see {@code TokenPepperInitializer}).
     *
     * When set, {@link #tokenHash(String)} uses HMAC-SHA256(token, pepper). A database
     * breach that does not also leak the pepper (it lives in env/KMS, not the DB) yields
     * useless hashes — an attacker cannot verify stolen tokens or guesses offline.
     *
     * When unset (local dev / tests), {@link #tokenHash(String)} falls back to plain
     * SHA-256 so existing behaviour holds without configuration.
     */
    private static volatile String pepper = null;

    /** Set the credential-hashing pepper. Intended to be called once at startup. */
    public static void setPepper(String value) {
        pepper = (value != null && !value.isBlank()) ? value : null;
    }

    public static boolean isPepperSet() {
        return pepper != null;
    }

    /**
     * Hash a credential (API key, session token) for storage/lookup.
     * Uses HMAC-SHA256 with the server pepper when configured, else SHA-256.
     * Both the store and the lookup paths call this, so they stay consistent.
     */
    public static String tokenHash(String rawCredential) {
        String p = pepper;
        return (p != null) ? hmacSha256(rawCredential, p) : sha256(rawCredential);
    }

    /** Plain SHA-256 hex. For non-credential hashing only (credentials use {@link #tokenHash}). */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Keyed HMAC-SHA256 hex. */
    public static String hmacSha256(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
