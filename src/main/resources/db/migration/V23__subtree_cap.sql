-- V23: Per-chain spend cap (maxSubtreeQuantity)
--
-- chain_root_event_id: denormalized pointer to the root of each causal chain.
--   Root events (no parent):  chain_root_event_id = id (self-referential)
--   Child events:              chain_root_event_id = parent.chain_root_event_id
--
-- max_subtree_quantity: optional cap on the total AUTHORIZED + CONFIRMED spend
--   across the entire causal chain rooted at this event. Only meaningful on root
--   events (chain_root_event_id = id). Null = no cap.
--
-- When a child event is being authorized, the service looks up the chain root's
-- max_subtree_quantity and sums all AUTHORIZED + CONFIRMED requestedQuantity for
-- events with the same chain_root_event_id. If the projected total would exceed the
-- cap, the request is denied with SUBTREE_CAP_EXCEEDED.
--
-- Backfill note: existing root events (parent_event_id IS NULL) are backfilled to
-- point at themselves. Existing child events are left NULL — the cap check skips
-- events without a chain_root_event_id, so all legacy chains are unaffected.

ALTER TABLE spend_events
    ADD COLUMN chain_root_event_id UUID REFERENCES spend_events(id),
    ADD COLUMN max_subtree_quantity NUMERIC(19, 4);

-- Index for the subtree total query: sumSubtreeQuantity filters by chain_root_event_id
-- This is hit on every authorize() call that has a parentEventId and a cap is active.
CREATE INDEX idx_spend_events_chain_root
    ON spend_events (chain_root_event_id)
    WHERE chain_root_event_id IS NOT NULL;

-- Backfill: root events are their own chain root
UPDATE spend_events
SET chain_root_event_id = id
WHERE parent_event_id IS NULL;
