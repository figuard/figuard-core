-- Records the exact timestamp a budget was cancelled.
-- Null on non-cancelled budgets. Used for audit trail and sweep exclusion.
ALTER TABLE agent_budgets ADD COLUMN cancelled_at TIMESTAMPTZ;
