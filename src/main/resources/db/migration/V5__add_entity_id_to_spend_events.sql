-- entityId: first-class identifier for the real-world entity this spend event relates to.
-- Examples: invoice_123, order_456, booking_789
-- Enables deduplication per entity, entity-scoped queries, and concurrency grouping
-- without the caller having to encode entity context into the idempotency key.
ALTER TABLE spend_events ADD COLUMN entity_id VARCHAR(255);
CREATE INDEX idx_spend_events_entity ON spend_events(entity_id) WHERE entity_id IS NOT NULL;
