-- V24: support for externally-recorded spend events
--
-- External events are spend records for actions that happened outside of the
-- normal authorize → confirm flow (e.g. a human manually processed a payment
-- in QuickBooks). They are created directly in CONFIRMED state via the
-- POST /api/v1/events/external endpoint.
--
-- event_source: identifies the origin of the event. NULL for standard AGENT
--   events; set to HUMAN or EXTERNAL for events recorded via the external
--   endpoint. Stored only when non-null to keep the column sparse.
--
-- occurred_at: when the action actually happened in the real world.
--   Differs from created_at (when it was recorded in FiGuard).
--   NULL for standard events where occurred_at ≈ created_at.

ALTER TABLE spend_events
    ADD COLUMN event_source VARCHAR(20),
    ADD COLUMN occurred_at  TIMESTAMPTZ;

COMMENT ON COLUMN spend_events.event_source IS
    'Origin of the event: NULL = AGENT (standard), HUMAN, or EXTERNAL. Set only by POST /events/external.';

COMMENT ON COLUMN spend_events.occurred_at IS
    'When the action happened in the real world. NULL for standard events (created_at is accurate enough).';
