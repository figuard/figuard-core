-- Tracks whether a RENEWAL_TOKEN_DELIVERY_FAILED alert has been dispatched
-- for a failed entitlement.renewed delivery. Prevents re-firing on every sweep pass.
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS renewal_alert_sent BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for the retry sweep — finds FAILED deliveries due for re-attempt.
-- Partial index: only rows that are candidates (status=FAILED, attempt_count < 10).
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_retry
    ON webhook_deliveries (next_retry_at ASC)
    WHERE status = 'FAILED' AND attempt_count < 10;
