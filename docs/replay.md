# Replay & Audit

FiGuard records every authorization decision — authorized, denied, confirmed, failed, voided — in an append-only ledger. Nothing is ever overwritten.

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

## Counterfactual replay

Given a budget's history, you can ask: "What would have happened if the budget had been configured differently?"

```bash
curl -X POST \
  -H "X-Agent-Budget-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "hypotheticalConfig": {
      "totalLimit": 800.00,
      "allocations": [
        {"category": "flight", "limit": 400.00, "enforcement_mode": "CATEGORY_CONSTRAINED"},
        {"category": "hotel",  "limit": 400.00, "enforcement_mode": "CATEGORY_CONSTRAINED"}
      ]
    }
  }' \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/counterfactual"
```

The response replays every event in the ledger against the hypothetical configuration and returns:

```json
{
  "originalAuthorized": 2,
  "originalDenied": 1,
  "hypotheticalAuthorized": 3,
  "hypotheticalDenied": 0,
  "events": [
    {
      "eventId": "...",
      "originalDecision": "AUTHORIZED",
      "hypotheticalDecision": "AUTHORIZED",
      "changed": false
    },
    {
      "eventId": "...",
      "originalDecision": "DENIED",
      "hypotheticalDecision": "AUTHORIZED",
      "changed": true,
      "reason": "allocation limit raised from 200 to 400"
    }
  ]
}
```

Use this to tune budget configurations before deploying to production, or to do post-incident analysis after an agent run.

---

## Spend tree

For fleet budgets, the ledger is organized as a tree: the orchestrator budget at the root, delegation tokens as branches, and individual authorization events as leaves.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$FLEET_BUDGET_ID/spend-tree"
```

The response shows which sub-agent authorized what, which events were confirmed vs voided, and the rolled-up totals at each level.
