-- V7: Anomaly detection and auto-pause
-- Per-budget configuration columns on agent_budgets
ALTER TABLE agent_budgets
    ADD COLUMN anomaly_detection_enabled       BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN anomaly_pause_threshold_multiplier NUMERIC(5,2)           DEFAULT 3.0,
        -- Auto-pause when requestedAmount > historicalMean * multiplier. Default 3x.
    ADD COLUMN anomaly_min_sample_size         INTEGER                    DEFAULT 5,
        -- Skip anomaly check until at least this many CONFIRMED events have built the baseline.
        -- Prevents false positives on brand-new budgets.
    ADD COLUMN anomaly_alert_webhook_url       VARCHAR(2000);
        -- Optional dedicated webhook URL for anomaly alerts.
        -- Falls back to tenant webhook configs if null.

-- Per-budget rolling baseline — updated asynchronously after every CONFIRMED event.
-- Stores the Welford running mean so the anomaly check can compare without loading history.
CREATE TABLE budget_anomaly_baselines (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id        UUID         NOT NULL REFERENCES agent_budgets(id) UNIQUE,
    tenant_id        UUID         NOT NULL REFERENCES tenants(id),
    sample_count     INTEGER      NOT NULL DEFAULT 0,
    mean_amount      NUMERIC(19,4),   -- Welford running mean of confirmed amounts
    std_dev_amount   NUMERIC(19,4),   -- Running std dev (informational, not used in check)
    min_amount       NUMERIC(19,4),
    max_amount       NUMERIC(19,4),
    last_updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_anomaly_baselines_budget ON budget_anomaly_baselines(budget_id);
