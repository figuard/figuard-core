ALTER TABLE agent_budgets
    ADD COLUMN IF NOT EXISTS velocity_max_per_minute      INT,
    ADD COLUMN IF NOT EXISTS velocity_max_amount_per_hour NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS velocity_max_per_day         INT;

CREATE INDEX IF NOT EXISTS idx_spend_events_velocity_dedup
    ON spend_events (budget_id, created_at DESC)
    WHERE denial_reason = 'VELOCITY_LIMIT_EXCEEDED';
