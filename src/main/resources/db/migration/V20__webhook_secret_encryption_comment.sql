-- Webhook secrets are now encrypted at the application layer (AES-256-GCM).
-- Values written after this migration are stored as "enc:<Base64(nonce || ciphertext || authtag)>".
-- Legacy plaintext values in existing rows remain readable — WebhookSecretEncryptor.decrypt()
-- falls back transparently for values without the "enc:" prefix.
-- To fully migrate existing rows: delete and re-register affected webhook configs.
-- The VARCHAR(512) widening accommodates the encryption overhead on top of the raw secret.
ALTER TABLE webhook_configs
    ALTER COLUMN secret TYPE VARCHAR(512);

COMMENT ON COLUMN webhook_configs.secret IS
    'AES-256-GCM encrypted HMAC signing secret. '
    'Format: enc:<Base64(nonce||ciphertext||authtag)>. '
    'Decrypt with WEBHOOK_SECRET_KEY env var. '
    'Legacy plaintext values (no enc: prefix) are handled transparently.';
