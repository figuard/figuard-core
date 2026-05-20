-- V22: Link agent_budgets to subscription entitlement items (known-user path).
-- Both columns are nullable — null means standalone budget (existing behavior unchanged).

ALTER TABLE agent_budgets
    ADD COLUMN subscription_id    UUID REFERENCES subscriptions(id),
    ADD COLUMN entitlement_item_id UUID REFERENCES entitlement_items(id);

CREATE INDEX idx_budgets_subscription
    ON agent_budgets (subscription_id)
    WHERE subscription_id IS NOT NULL;

CREATE INDEX idx_budgets_entitlement_item
    ON agent_budgets (entitlement_item_id)
    WHERE entitlement_item_id IS NOT NULL;
