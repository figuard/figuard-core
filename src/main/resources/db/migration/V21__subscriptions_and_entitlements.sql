-- V21: Subscription and entitlement model
-- Adds: subscriptions, entitlement_items, entitlement_state_transitions,
--       subscription_renewal_logs, and entitlement_item_id on spend_events.

-- ─────────────────────────────────────────────────────────────────────────────
-- subscriptions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE subscriptions (
    id                       UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                UUID        NOT NULL REFERENCES tenants(id),
    external_subscriber_id   TEXT        NOT NULL,
    name                     TEXT        NOT NULL,
    description              TEXT,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    subscription_start_date  TIMESTAMPTZ NOT NULL,
    metadata                 JSONB,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                  BIGINT      NOT NULL DEFAULT 0
);

-- One subscription per external_subscriber_id per tenant
CREATE UNIQUE INDEX idx_subscriptions_tenant_subscriber
    ON subscriptions (tenant_id, external_subscriber_id);

CREATE INDEX idx_subscriptions_tenant_status
    ON subscriptions (tenant_id, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- entitlement_items
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE entitlement_items (
    id                          UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    subscription_id             UUID           NOT NULL REFERENCES subscriptions(id),
    name                        TEXT           NOT NULL,
    limit_unit                  VARCHAR(30)    NOT NULL,
    limit_quantity              NUMERIC(20, 6) NOT NULL,
    warn_at_percentage          INT            NOT NULL DEFAULT 80,
    renewal_period              VARCHAR(20)    NOT NULL,
    next_renewal_at             TIMESTAMPTZ    NOT NULL,
    overage_policy              VARCHAR(20)    NOT NULL DEFAULT 'BLOCK',
    state                       VARCHAR(20)    NOT NULL DEFAULT 'NORMAL',
    current_period_consumed     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    last_state_transition_at    TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                     BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_entitlement_items_subscription
    ON entitlement_items (subscription_id);

-- Renewal sweep index: find items due for renewal
CREATE INDEX idx_entitlement_items_next_renewal
    ON entitlement_items (next_renewal_at)
    WHERE state != 'LIMIT_REACHED';

-- ─────────────────────────────────────────────────────────────────────────────
-- entitlement_state_transitions  (audit trail)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE entitlement_state_transitions (
    id                               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    entitlement_item_id              UUID        NOT NULL REFERENCES entitlement_items(id),
    from_state                       VARCHAR(20) NOT NULL,
    to_state                         VARCHAR(20) NOT NULL,
    consumed_percentage_at_transition INT        NOT NULL,
    trigger_reason                   VARCHAR(50) NOT NULL,
    transitioned_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_state_transitions_item
    ON entitlement_state_transitions (entitlement_item_id, transitioned_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- subscription_renewal_logs
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE subscription_renewal_logs (
    id                        UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    subscription_id           UUID           NOT NULL REFERENCES subscriptions(id),
    entitlement_item_id       UUID           NOT NULL REFERENCES entitlement_items(id),
    period_consumed_quantity  NUMERIC(20, 6) NOT NULL,
    new_period_limit          NUMERIC(20, 6) NOT NULL,
    renewal_executed_at       TIMESTAMPTZ    NOT NULL,
    new_period_ends_at        TIMESTAMPTZ    NOT NULL,
    result                    VARCHAR(20)    NOT NULL,
    webhook_delivery_id       UUID,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_renewal_logs_subscription
    ON subscription_renewal_logs (subscription_id, renewal_executed_at DESC);

CREATE INDEX idx_renewal_logs_item
    ON subscription_renewal_logs (entitlement_item_id, renewal_executed_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- spend_events — add nullable entitlement_item_id
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE spend_events
    ADD COLUMN entitlement_item_id UUID REFERENCES entitlement_items(id);

CREATE INDEX idx_spend_events_entitlement_item
    ON spend_events (entitlement_item_id)
    WHERE entitlement_item_id IS NOT NULL;
