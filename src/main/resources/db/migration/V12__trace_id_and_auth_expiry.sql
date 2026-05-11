-- V12: trace_id on spend_events + authorization_expiry_seconds on agent_budgets
--
-- trace_id (1a): links all events from a single agent run across tool calls.
-- Stored as VARCHAR — callers supply their own run ID (UUID, span ID, etc.)
-- Indexed for ledger filtering: GET /api/v1/budgets/{id}/ledger?traceId=...
--
-- authorization_expiry_seconds (1b): lazy auto-expiry for stale reservations.
-- When set, AUTHORIZED events older than this window are excluded from the
-- available-quantity calculation. Orphaned reservations age out without a
-- background job. Evaluated at authorization time, not via a cron sweep.

ALTER TABLE spend_events
    ADD COLUMN trace_id VARCHAR(255);

CREATE INDEX idx_spend_events_trace_id
    ON spend_events(trace_id)
    WHERE trace_id IS NOT NULL;

ALTER TABLE agent_budgets
    ADD COLUMN authorization_expiry_seconds INTEGER;
