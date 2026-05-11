-- V11: Rename amount fields to quantity, add unit field for resource budgets
-- This makes the enforcement model explicit: FiGuard enforces dimensionless quantities.
-- Monetary budgets use currency; resource budgets (tokens, API calls, etc.) use unit.

-- agent_budgets: rename amount columns, add unit, make currency nullable
ALTER TABLE agent_budgets
    RENAME COLUMN amount_spent TO quantity_spent;

ALTER TABLE agent_budgets
    RENAME COLUMN amount_reserved TO quantity_reserved;

ALTER TABLE agent_budgets
    RENAME COLUMN max_transaction_amount TO max_transaction_quantity;

ALTER TABLE agent_budgets
    ADD COLUMN unit VARCHAR(50);

-- currency becomes nullable: NULL means resource budget (unit is set instead)
ALTER TABLE agent_budgets
    ALTER COLUMN currency DROP NOT NULL;

ALTER TABLE agent_budgets
    ALTER COLUMN currency DROP DEFAULT;

-- budget_allocations: rename amount columns, make currency nullable
ALTER TABLE budget_allocations
    RENAME COLUMN amount_spent TO quantity_spent;

ALTER TABLE budget_allocations
    RENAME COLUMN amount_reserved TO quantity_reserved;

ALTER TABLE budget_allocations
    ALTER COLUMN currency DROP NOT NULL;

ALTER TABLE budget_allocations
    ALTER COLUMN currency DROP DEFAULT;

-- spend_events: rename amount columns, make currency nullable
ALTER TABLE spend_events
    RENAME COLUMN requested_amount TO requested_quantity;

ALTER TABLE spend_events
    RENAME COLUMN confirmed_amount TO confirmed_quantity;

ALTER TABLE spend_events
    ALTER COLUMN currency DROP NOT NULL;

ALTER TABLE spend_events
    ALTER COLUMN currency DROP DEFAULT;
