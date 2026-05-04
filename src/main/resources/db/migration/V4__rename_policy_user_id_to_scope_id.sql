-- Rename user_id → scope_id on budget_policies.
-- Rationale: a policy applies to a fleet, team, operation type, or user —
-- the caller decides what the scope means. "user_id" implied per-user only,
-- which is too narrow. scope_id is intentionally generic.
ALTER TABLE budget_policies RENAME COLUMN user_id TO scope_id;
