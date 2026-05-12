-- Phase 3: Scoped Delegation Tokens
--
-- Adds two tables that power the delegation token feature:
--   delegated_tokens          — one row per sub-agent credential
--   delegated_token_allocations — per-category caps for each token
--
-- Also adds delegated_token_id to spend_events so the lifecycle service
-- (confirm/fail/void) can update the per-token category cap counters.

CREATE TABLE delegated_tokens (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_budget_id     UUID         NOT NULL REFERENCES agent_budgets(id),
    tenant_id            UUID         NOT NULL REFERENCES tenants(id),
    session_token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    session_token_prefix VARCHAR(12)  NOT NULL,
    label                VARCHAR(255) NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    revoked_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0
);

-- Fast lookup in the authorize hot path (active tokens only)
CREATE INDEX idx_delegated_tokens_hash_active
    ON delegated_tokens(session_token_hash)
    WHERE status = 'ACTIVE';

-- Dashboard / list queries by parent budget
CREATE INDEX idx_delegated_tokens_parent_budget
    ON delegated_tokens(parent_budget_id);

CREATE TABLE delegated_token_allocations (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    delegated_token_id  UUID          NOT NULL REFERENCES delegated_tokens(id),
    category            VARCHAR(255)  NOT NULL,
    total_limit         NUMERIC(19,4) NOT NULL,
    quantity_spent      NUMERIC(19,4) NOT NULL DEFAULT 0,
    quantity_reserved   NUMERIC(19,4) NOT NULL DEFAULT 0,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (delegated_token_id, category)
);

-- Audit trail: which delegation token was used for each spend event.
-- NULL for events created via a direct budget session token.
ALTER TABLE spend_events
    ADD COLUMN IF NOT EXISTS delegated_token_id UUID
        REFERENCES delegated_tokens(id);

-- Index for looking up all events created via a specific delegation token
CREATE INDEX idx_spend_events_delegated_token
    ON spend_events(delegated_token_id)
    WHERE delegated_token_id IS NOT NULL;
