-- Allow webhook_deliveries to record direct-URL dispatches (e.g. anomalyAlertWebhookUrl)
-- where there is no WebhookConfig row. Previously these were fire-and-forget with no record.

ALTER TABLE webhook_deliveries
    ALTER COLUMN webhook_config_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS target_url VARCHAR(2048);

-- For config-backed deliveries target_url stays NULL (join to webhook_configs to get url).
-- For direct-URL deliveries webhook_config_id stays NULL and target_url holds the destination.
-- Constraint: exactly one must be set.
ALTER TABLE webhook_deliveries
    ADD CONSTRAINT chk_delivery_has_destination
        CHECK (
            (webhook_config_id IS NOT NULL AND target_url IS NULL)
            OR
            (webhook_config_id IS NULL AND target_url IS NOT NULL)
        );

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_tenant_status
    ON webhook_deliveries (webhook_config_id, status, created_at DESC)
    WHERE webhook_config_id IS NOT NULL;
