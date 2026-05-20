# Replay & Audit

FiGuard records every authorization decision — authorized, denied, confirmed, failed, voided — in an append-only ledger. Nothing is ever overwritten. The replay API lets you read that history in four different ways depending on what you need.

---

## Reading the ledger

```python
page = client.get_ledger(budget_id=budget.id, page=0, size=20)

for event in page.events:
    print(event.decision, event.requested_quantity, event.description)
    # AUTHORIZED  270.0  JetBlue SFO→JFK roundtrip
    # DENIED      150.0  Travel insurance — NO_MATCHING_ALLOCATION
    # AUTHORIZED  198.0  Marriott Times Square 2 nights
```

Or via the API directly:

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/ledger?page=0&size=50"
```

Each ledger entry includes:

| Field | Description |
|---|---|
| `id` | Unique event ID |
| `decision` | `AUTHORIZED` or `DENIED` |
| `denialCode` | Machine-readable denial reason (null if authorized) |
| `agentId` | Which agent made the request |
| `actionType` | `PURCHASE`, `REFUND`, `LLM_CALL`, `EXTERNAL_CALL`, etc. |
| `requestedQuantity` | Amount the agent requested |
| `approvedQuantity` | Amount reserved (null if denied) |
| `confirmedQuantity` | Amount actually consumed (null until confirmed) |
| `status` | `AUTHORIZED`, `CONFIRMED`, `FAILED`, `VOIDED` |
| `idempotencyKey` | The key the agent provided |
| `createdAt` | When the authorization was evaluated |

---

## Full event replay with state snapshots

`GET /api/v1/budgets/{id}/replay` replays every event in the ledger and returns the projected budget state after each one. This is useful when you want to understand exactly how the budget reached its current state — not just the final numbers, but the step-by-step accounting.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay"
```

Response — one entry per event, each with a `budgetStateAfter` snapshot:

```json
{
  "events": [
    {
      "eventId": "evt_001",
      "decision": "AUTHORIZED",
      "requestedQuantity": 270.00,
      "budgetStateAfter": {
        "quantitySpent": 0.00,
        "quantityReserved": 270.00,
        "availableQuantity": 230.00
      }
    },
    {
      "eventId": "evt_002",
      "decision": "CONFIRMED",
      "confirmedQuantity": 267.00,
      "budgetStateAfter": {
        "quantitySpent": 267.00,
        "quantityReserved": 0.00,
        "availableQuantity": 233.00
      }
    }
  ]
}
```

---

## Point-in-time state projection

`GET /api/v1/budgets/{id}/replay/state?at=<ISO8601>` returns the budget state as it was at a specific moment — ignoring all events that happened after that timestamp.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/state?at=2026-05-19T10:00:00Z"
```

**Post-incident analysis scenario:** Your agent fleet ran overnight and you woke up to a fully exhausted budget. Point-in-time projection lets you reconstruct what the budget looked like at any moment during the run — for example, at 2 AM when the first anomaly-adjacent spend happened — without any guesswork.

---

## Timeline

`GET /api/v1/budgets/{id}/replay/timeline` returns a lightweight chronological summary without the full state snapshots — just event IDs, decisions, amounts, and timestamps. Faster than full replay when you only need the sequence.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/timeline"
```

```json
{
  "events": [
    { "eventId": "evt_001", "decision": "AUTHORIZED", "quantity": 270.00, "at": "2026-05-19T09:00:00Z" },
    { "eventId": "evt_002", "decision": "CONFIRMED",  "quantity": 267.00, "at": "2026-05-19T09:01:12Z" },
    { "eventId": "evt_003", "decision": "DENIED",     "quantity": 198.00, "denialCode": "ALLOCATION_EXHAUSTED", "at": "2026-05-19T09:05:44Z" }
  ]
}
```

---

## Counterfactual replay

`POST /api/v1/budgets/{id}/replay/counterfactual` replays the real event history against a hypothetical budget configuration and shows you what would have changed.

**Tuning budget configurations before production:** Suppose your agent fleet ran overnight and spent $4,200. You want to add a $3,000 cap on the `compute` allocation before the next run — but you're not sure how many legitimate requests that would have blocked. Counterfactual replay answers that without you needing to re-run the agents:

```bash
curl -X POST \
  -H "X-Agent-Budget-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "hypotheticalConfig": {
      "totalLimit": 5000.00,
      "allocations": [
        {"category": "compute", "limit": 3000.00, "enforcement_mode": "CATEGORY_CONSTRAINED"},
        {"category": "storage", "limit": 2000.00, "enforcement_mode": "CATEGORY_CONSTRAINED"}
      ]
    }
  }' \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/counterfactual"
```

Response:

```json
{
  "originalAuthorized": 47,
  "originalDenied": 3,
  "hypotheticalAuthorized": 41,
  "hypotheticalDenied": 9,
  "events": [
    {
      "eventId": "evt_031",
      "originalDecision": "AUTHORIZED",
      "hypotheticalDecision": "DENIED",
      "changed": true,
      "hypotheticalDenialCode": "ALLOCATION_EXHAUSTED",
      "reason": "compute allocation would have been exhausted at $2,980 spent"
    }
  ]
}
```

The result: a $3,000 compute cap would have caught the overspend at $2,980 with 6 additional denials. You can now decide whether those 6 denials are acceptable, or whether $3,500 is a better threshold — and run the counterfactual again to check, all against the same real event history.

**Post-incident analysis:** If an agent run caused unexpected spend, use counterfactual replay to test what velocity controls or allocation limits would have intercepted it, and at which specific event. The `changed: true` entries are your investigation starting points.

---

## Spend tree

For fleet budgets, the ledger is organized as a tree: the orchestrator budget at the root, delegation tokens as branches, and individual authorization events as leaves.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$FLEET_BUDGET_ID/spend-tree"
```

The response shows which sub-agent authorized what, which events were confirmed vs voided, and the rolled-up totals at each level.
