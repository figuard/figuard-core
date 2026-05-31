-- V25: budget-level trust mode
--
-- SHADOW mode: all enforcement checks run but nothing is blocked.
-- The authorize response returns AUTHORIZED with shadow=true and
-- wouldHaveBeen/wouldHaveBeenReason showing what would have happened.
-- No SpendEvent is written in shadow mode (consistent with dryRun semantics).
--
-- FULL_ENFORCEMENT (default): existing behaviour, unchanged.

ALTER TABLE agent_budgets
    ADD COLUMN trust_mode VARCHAR(20) NOT NULL DEFAULT 'FULL_ENFORCEMENT';

COMMENT ON COLUMN agent_budgets.trust_mode IS
    'SHADOW = run all enforcement checks but never block; '
    'FULL_ENFORCEMENT = default behaviour (deny on limit breach).';
