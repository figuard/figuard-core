-- Spend receipts: one shareable receipt per budget session.
-- Generated lazily on first GET /api/v1/budgets/{id}/receipt.
-- The receipt_token is a random 32-char URL-safe string — no auth needed to view.

CREATE TABLE spend_receipts (
    id              UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    budget_id       UUID         NOT NULL REFERENCES agent_budgets(id),
    receipt_token   VARCHAR(32)  NOT NULL UNIQUE,
    generated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_spend_receipts_budget_id ON spend_receipts(budget_id);
