package com.figuard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for webhook signing secrets stored in the database.
 *
 * Encrypted values are stored with an "enc:" prefix so they can be distinguished
 * from legacy plaintext secrets. decrypt() falls back transparently for any value
 * without the prefix, giving a zero-downtime migration path: existing configs
 * continue to work; new configs (and re-created ones) get encrypted storage.
 *
 * Key is configured via WEBHOOK_SECRET_KEY env var (Base64-encoded 32 bytes).
 * Generate a production key with: openssl rand -base64 32
 *
 * The application-layer key means a compromised DB backup does not expose secrets —
 * an attacker needs both the DB content and the WEBHOOK_SECRET_KEY to forge webhooks.
 */
@Component
public class WebhookSecretEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    static final String PREFIX = "enc:";

    private final SecretKeySpec keySpec;

    public WebhookSecretEncryptor(
            @Value("${agent-billing.webhook.secret-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "agent-billing.webhook.secret-key must decode to exactly 32 bytes " +
                "(256-bit AES key). Generate with: openssl rand -base64 32");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a plaintext secret, returning "enc:<Base64(nonce || ciphertext || authtag)>".
     * A fresh 12-byte random nonce is generated for every call.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Store nonce prepended to ciphertext (ciphertext already includes 16-byte GCM auth tag)
            byte[] combined = new byte[NONCE_BYTES + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, NONCE_BYTES);
            System.arraycopy(ciphertext, 0, combined, NONCE_BYTES, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt webhook secret", e);
        }
    }

    /**
     * Decrypts an encrypted secret. Falls back transparently for legacy plaintext
     * values (those without the "enc:" prefix) — no-op, returns value as-is.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;  // legacy plaintext — pass through unchanged
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);
            byte[] ciphertext = new byte[combined.length - NONCE_BYTES];
            System.arraycopy(combined, NONCE_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt webhook secret", e);
        }
    }
}
