-- Phase 2: expiringSoonNotified flag on agent_budgets.
-- Set to TRUE once the BUDGET_EXPIRING_SOON webhook has been dispatched.
-- Prevents the sweep job from re-firing the notification on every subsequent pass.
ALTER TABLE agent_budgets
    ADD COLUMN IF NOT EXISTS expiring_soon_notified BOOLEAN NOT NULL DEFAULT FALSE;
