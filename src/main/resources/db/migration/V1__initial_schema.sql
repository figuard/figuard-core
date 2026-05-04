-- =============================================================================
-- Spentinel V1 Schema
-- =============================================================================

-- Tenants (account-level isolation)
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- API Keys (auth)
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    key_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 of actual key
    key_prefix VARCHAR(8) NOT NULL,        -- first 8 chars for display (e.g. "ab_live_")
    description VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);

-- Agent Budgets
CREATE TABLE agent_budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(255) NOT NULL,               -- end user who authorized this budget (your app's user ID)
    session_token_hash VARCHAR(64) NOT NULL UNIQUE, -- SHA-256 of session token; token presented by agents at authorize time
    session_token_prefix VARCHAR(12) NOT NULL,    -- first 12 chars for display/debugging (e.g. "st_Xk9mP2nQ")
    external_reference VARCHAR(255),             -- caller's own ID for this budget (e.g. user_session_id)
    intent_context VARCHAR(1000) NOT NULL,
    intent_tags TEXT[],
    total_limit NUMERIC(19,4) NOT NULL,          -- max spend allowed
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    amount_spent NUMERIC(19,4) NOT NULL DEFAULT 0,
    amount_reserved NUMERIC(19,4) NOT NULL DEFAULT 0,  -- authorized but not yet confirmed
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | PAUSED | EXHAUSTED | CANCELLED | EXPIRED
    soft_limit NUMERIC(19,4),                    -- threshold for warning webhooks (optional)
    expires_at TIMESTAMPTZ NOT NULL,             -- REQUIRED — reject budget creation without this; max 24h in future
    first_authorize_deadline TIMESTAMPTZ NOT NULL -- auto-expire if no authorize call arrives by this time
        DEFAULT NOW() + INTERVAL '15 minutes',
    previous_session_token_hash VARCHAR(64),     -- held during token rotation grace period
    token_rotation_expires_at TIMESTAMPTZ,       -- when previous_session_token_hash stops being valid
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,           -- optimistic locking
    CONSTRAINT chk_total_limit_positive CHECK (total_limit > 0),
    CONSTRAINT chk_amount_spent_non_negative CHECK (amount_spent >= 0),
    CONSTRAINT chk_valid_status CHECK (status IN ('ACTIVE','PAUSED','EXHAUSTED','CANCELLED','EXPIRED'))
);
CREATE INDEX idx_agent_budgets_tenant ON agent_budgets(tenant_id);
CREATE INDEX idx_agent_budgets_user ON agent_budgets(tenant_id, user_id);
CREATE INDEX idx_agent_budgets_session_token ON agent_budgets(session_token_hash);
CREATE INDEX idx_agent_budgets_external_ref ON agent_budgets(tenant_id, external_reference);
CREATE INDEX idx_agent_budgets_status ON agent_budgets(status);

-- Budget Allocations (per-category spend envelopes within a parent budget)
-- ENFORCEMENT MODEL: agents must declare structured intent — no text inference.
-- Matching is direct equality: allowedCategories.contains(claimedCategory)
CREATE TABLE budget_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_budget_id UUID NOT NULL REFERENCES agent_budgets(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    category VARCHAR(255) NOT NULL,              -- primary label e.g. "flight", "hotel"
    allowed_categories TEXT[] NOT NULL,           -- agent claimedCategory must exactly match one of these
    forbidden_item_types TEXT[],                  -- optional blocklist for STRICT mode
    enforcement_mode VARCHAR(20) NOT NULL DEFAULT 'CATEGORY_CONSTRAINED',
        -- OPEN | CATEGORY_CONSTRAINED | STRICT
    total_limit NUMERIC(19,4) NOT NULL,
    amount_spent NUMERIC(19,4) NOT NULL DEFAULT 0,    -- permanently deducted (CONFIRMED events only)
    amount_reserved NUMERIC(19,4) NOT NULL DEFAULT 0,  -- held for AUTHORIZED events awaiting confirmation
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | EXHAUSTED | PAUSED
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_allocation_limit_positive CHECK (total_limit > 0),
    CONSTRAINT chk_allocation_spent_non_negative CHECK (amount_spent >= 0),
    CONSTRAINT chk_allocation_reserved_non_negative CHECK (amount_reserved >= 0),
    CONSTRAINT chk_enforcement_mode CHECK (enforcement_mode IN ('OPEN','CATEGORY_CONSTRAINED','STRICT')),
    CONSTRAINT uq_budget_category UNIQUE (parent_budget_id, category)
);
CREATE INDEX idx_allocations_parent ON budget_allocations(parent_budget_id);
CREATE INDEX idx_allocations_tenant ON budget_allocations(tenant_id);

-- Spend Events (the ledger)
-- Payment lifecycle: AUTHORIZED → CONFIRMED (payment succeeded) or FAILED (payment failed)
-- DENIED = authorization rejected before any payment was attempted
-- VOIDED = manually cancelled (only valid on AUTHORIZED events)
CREATE TABLE spend_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    budget_id UUID NOT NULL REFERENCES agent_budgets(id),
    parent_event_id UUID REFERENCES spend_events(id),  -- for causal chain tracking
    root_budget_id UUID NOT NULL,                       -- always the top-level budget UUID
    agent_id VARCHAR(255) NOT NULL,                     -- identifier of the agent making request (observability only)
    agent_type VARCHAR(100),
    action_type VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    requested_amount NUMERIC(19,4) NOT NULL,
    confirmed_amount NUMERIC(19,4),                     -- actual amount charged (may differ slightly from requested)
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    -- Structured intent declaration (used for enforcement — NOT intentContext)
    claimed_category VARCHAR(255),                      -- agent's explicit category declaration e.g. "flight"
    claimed_item_type VARCHAR(255),                     -- agent's explicit item type e.g. "airline_ticket"
    decision VARCHAR(12) NOT NULL,
        -- AUTHORIZED | CONFIRMED | FAILED | DENIED | VOIDED
    denial_reason VARCHAR(500),
    failure_reason VARCHAR(500),
    intent_context VARCHAR(1000),                       -- logging/audit only, never used for enforcement
    allocation_id UUID REFERENCES budget_allocations(id),
    idempotency_key VARCHAR(255) NOT NULL,
    external_transaction_id VARCHAR(255),
    confirmation_timeout_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,                  -- optimistic locking — prevents confirm/fail/void race
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_decision CHECK (decision IN ('AUTHORIZED','CONFIRMED','FAILED','DENIED','VOIDED')),
    CONSTRAINT chk_requested_amount_positive CHECK (requested_amount > 0),
    CONSTRAINT uq_budget_idempotency UNIQUE (budget_id, idempotency_key)
);
CREATE INDEX idx_spend_events_budget ON spend_events(budget_id);
CREATE INDEX idx_spend_events_tenant ON spend_events(tenant_id);
CREATE INDEX idx_spend_events_parent ON spend_events(parent_event_id);
CREATE INDEX idx_spend_events_root_budget ON spend_events(root_budget_id);
CREATE INDEX idx_spend_events_agent ON spend_events(agent_id);
CREATE INDEX idx_spend_events_idempotency ON spend_events(tenant_id, idempotency_key);
CREATE INDEX idx_spend_events_created_at ON spend_events(created_at);

-- Webhook Configurations
CREATE TABLE webhook_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    url VARCHAR(2000) NOT NULL,
    secret VARCHAR(255) NOT NULL,             -- HMAC signing secret
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    events TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhook_configs_tenant ON webhook_configs(tenant_id);

-- Webhook Delivery Log
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_config_id UUID NOT NULL REFERENCES webhook_configs(id),
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | DELIVERED | FAILED
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ShedLock — prevents duplicate @Scheduled job execution across multiple service instances
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

-- Budget Policies (v2) — recurring budgets that auto-reset on a schedule
-- Schema defined in v1 so v2 implementation doesn't require a breaking migration
CREATE TABLE budget_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(255) NOT NULL,
    policy_token_hash VARCHAR(64) NOT NULL UNIQUE,
    policy_token_prefix VARCHAR(12) NOT NULL,
    intent_context VARCHAR(1000) NOT NULL,
    intent_tags TEXT[],
    period_limit NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    period_type VARCHAR(20) NOT NULL,               -- DAILY | WEEKLY | MONTHLY | CUSTOM
    period_cron VARCHAR(100),                       -- required when period_type = CUSTOM
    timezone VARCHAR(50) NOT NULL,                  -- IANA timezone e.g. "America/New_York"
    rollover_unused BOOLEAN NOT NULL DEFAULT FALSE,
    max_rollover_amount NUMERIC(19,4),
    max_accumulated_limit NUMERIC(19,4),
    allocations_template JSONB,
    soft_limit NUMERIC(19,4),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | PAUSED | CANCELLED
    active_budget_id UUID REFERENCES agent_budgets(id),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_policy_limit_positive CHECK (period_limit > 0),
    CONSTRAINT chk_policy_status CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
    CONSTRAINT chk_rollover_requires_cap CHECK (
        rollover_unused = FALSE
        OR (max_rollover_amount IS NOT NULL AND max_accumulated_limit IS NOT NULL)
    )
);
CREATE INDEX idx_budget_policies_tenant ON budget_policies(tenant_id);
CREATE INDEX idx_budget_policies_token ON budget_policies(policy_token_hash);
CREATE INDEX idx_budget_policies_active ON budget_policies(status) WHERE status = 'ACTIVE';
