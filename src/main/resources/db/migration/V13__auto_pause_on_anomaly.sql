-- Phase 2: autoPauseOnAnomaly flag on agent_budgets.
-- When true (default, preserves existing behavior): anomaly detection pauses the budget.
-- When false: anomaly is denied and webhook fires, but budget stays ACTIVE (advisory mode).
ALTER TABLE agent_budgets
    ADD COLUMN IF NOT EXISTS auto_pause_on_anomaly BOOLEAN NOT NULL DEFAULT TRUE;
