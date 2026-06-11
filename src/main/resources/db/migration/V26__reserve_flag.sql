-- reserve=false authorize: a tree-root / coordinator marker that holds no capacity.
-- Such AUTHORIZED events must be excluded from "currently reserved" sums (capacity check
-- and ledger-integrity check), since they add nothing to agent_budgets.quantity_reserved.
-- Existing rows are genuine reservations, so default TRUE.
ALTER TABLE spend_events
    ADD COLUMN reserved BOOLEAN NOT NULL DEFAULT TRUE;
