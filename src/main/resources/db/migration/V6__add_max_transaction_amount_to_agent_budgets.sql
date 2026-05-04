-- Optional per-transaction ceiling on a budget.
-- When set, any single authorize request with requestedAmount > maxTransactionAmount is denied
-- with EXCEEDS_TRANSACTION_LIMIT, regardless of available funds.
-- NULL means no per-transaction cap is enforced.
ALTER TABLE agent_budgets ADD COLUMN max_transaction_amount NUMERIC(19, 4);
