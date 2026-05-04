-- Entity deduplication: opt-in flag per budget
-- Enforcement is done at the application layer (AuthorizationService step 2b),
-- not via a unique index, so that budgets with entityDedupEnabled=false are unrestricted.

ALTER TABLE agent_budgets
    ADD COLUMN entity_dedup_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Non-unique index to speed up the dedup lookup query when entityDedupEnabled=true
CREATE INDEX idx_spend_events_entity_id
    ON spend_events(budget_id, entity_id)
    WHERE entity_id IS NOT NULL;
