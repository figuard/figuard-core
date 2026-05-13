-- Budget Replay: index on spend_events(budget_id, created_at ASC)
-- Replay reads events in chronological order per budget. Without this index,
-- full table scans occur on every replay request as the ledger grows.
-- No schema changes required — replay is a read-only projection over existing data.

CREATE INDEX IF NOT EXISTS idx_spend_events_budget_timeline
    ON spend_events (budget_id, created_at ASC);
