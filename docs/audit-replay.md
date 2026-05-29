# Audit & Replay

FiGuard records every authorization decision — authorized, denied, confirmed, failed, voided — in an append-only ledger. Nothing is ever overwritten. The replay API lets you read that history in several ways depending on what you need.

---

## Reading the ledger

The ledger is the complete event history for a budget. Use `iter_events` to page through it without tracking page tokens manually:

```python
# Python — auto-paginates
for event in client.iter_events(budget_id="bdg_..."):
    print(event.decision, event.requested_quantity, event.description)
    # AUTHORIZED  270.0  JetBlue SFO→JFK roundtrip
    # DENIED      150.0  Travel insurance — NO_MATCHING_ALLOCATION
    # AUTHORIZED  198.0  Marriott Times Square 2 nights
```

```python
# Async version
async for event in client.iter_events(budget_id="bdg_..."):
    process(event)

# Filter to a specific decision
denied = list(client.iter_events(budget_id="bdg_...", decision="DENIED"))
```

Or page manually:

```python
page = client.get_ledger(budget_id=budget.id, page=0, size=20)
for event in page.events:
    print(event.decision, event.requested_quantity, event.description)
```

Via REST:

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/ledger?page=0&size=50"
```

**Ledger event fields:**

| Field | Description |
|---|---|
| `id` | Unique event ID |
| `decision` | `AUTHORIZED`, `DENIED`, `CONFIRMED`, `FAILED`, or `VOIDED` |
| `denial_code` | Machine-readable denial reason (null if authorized) |
| `agent_id` | Which agent made the request |
| `action_type` | `PURCHASE`, `REFUND`, `LLM_CALL`, `EXTERNAL_CALL`, etc. |
| `requested_quantity` | Amount the agent requested |
| `approved_quantity` | Amount reserved (null if denied) |
| `confirmed_quantity` | Amount actually consumed (null until confirmed) |
| `idempotency_key` | The key the agent provided |
| `created_at` | When the authorization was evaluated |

---

## The three replay methods

| Method | Returns | Use when |
|---|---|---|
| `get_budget_state_at` | `BudgetStateSnapshot` | You need the projected balance at a specific timestamp |
| `get_budget_timeline` | `BudgetTimeline` | You need the event sequence and timing, but not per-step balances |
| `replay_counterfactual` | Summary + delta list | You want to know how a different policy would have changed outcomes |

---

## `get_budget_state_at` — point-in-time balance

Replays all ledger events up to `at` and returns the resulting budget state. Nothing is read from live state — it's computed from the event log, so you get a precise answer regardless of what happened after that moment.

```python
# Python
from datetime import datetime, timezone

snapshot = client.get_budget_state_at(
    budget_id="bdg_...",
    at=datetime(2025, 5, 28, 14, 32, tzinfo=timezone.utc),
)

print(f"Projected at:    {snapshot.projected_at}")
print(f"Events applied:  {snapshot.events_applied}")
print(f"Total limit:     {snapshot.total_limit}")
print(f"Spent:           {snapshot.quantity_spent}")
print(f"Reserved:        {snapshot.quantity_reserved}")
print(f"Available:       {snapshot.available}")
print(f"Status:          {snapshot.budget_status}")

for alloc in snapshot.allocations:
    print(f"  {alloc.category}: {alloc.available} remaining of {alloc.limit}")
```

```typescript
// TypeScript
const snapshot = await client.getBudgetStateAt("bdg_...", "2025-05-28T14:32:00Z");

console.log(`Projected at:   ${snapshot.projectedAt}`);
console.log(`Events applied: ${snapshot.eventsApplied}`);
console.log(`Available:      ${snapshot.available}`);

for (const alloc of snapshot.allocations) {
  console.log(`  ${alloc.category}: ${alloc.available} of ${alloc.limit}`);
}
```

Via REST:

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/state?at=2025-05-28T14:32:00Z"
```

**`BudgetStateSnapshot` fields:**

| Field | Type | Description |
|---|---|---|
| `budget_id` | str | Budget ID |
| `projected_at` | str | The timestamp you queried |
| `events_applied` | int | How many events were replayed to reach this state |
| `total_limit` | float | Budget's total limit |
| `quantity_spent` | float | Sum of all CONFIRMED events up to `at` |
| `quantity_reserved` | float | Sum of outstanding AUTHORIZED events at `at` |
| `available` | float | `total_limit − quantity_spent − quantity_reserved` |
| `budget_status` | str | `"ACTIVE"`, `"PAUSED"`, `"EXHAUSTED"`, etc. |
| `allocations` | list | Per-category breakdown |

---

## `get_budget_timeline` — event sequence

Returns every event in chronological order with timing between events. No balance projections — use this when you want to see the sequence and timing, not reconstruct a balance.

```python
# Python
from datetime import datetime, timezone

timeline = client.get_budget_timeline(
    budget_id="bdg_...",
    from_time=datetime(2025, 5, 28, 13, 0, tzinfo=timezone.utc),
    until=datetime(2025, 5, 28, 15, 0, tzinfo=timezone.utc),
)

print(f"{timeline.total_events} events in window")
for event in timeline.timeline:
    gap = f"+{event.millis_since_previous / 1000:.1f}s" if event.millis_since_previous else "start"
    print(f"  [{gap:>8}] #{event.event_index}  {event.decision:12s}  {event.requested_quantity:>8.2f}  {event.agent_id}")
```

```typescript
// TypeScript
const tl = await client.getBudgetTimeline({
  budgetId: "bdg_...",
  from: "2025-05-28T13:00:00Z",
  until: "2025-05-28T15:00:00Z",
});

for (const e of tl.timeline) {
  const gap = e.millisSincePrevious != null
    ? `+${(e.millisSincePrevious / 1000).toFixed(1)}s`
    : "start";
  console.log(`  [${gap.padStart(8)}] #${e.eventIndex}  ${e.decision.padEnd(12)}  ${e.requestedQuantity}  ${e.agentId}`);
}
```

Via REST:

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$BUDGET_ID/replay/timeline"
```

**`TimelineEvent` fields:**

| Field | Type | Description |
|---|---|---|
| `event_index` | int | 0-based position in the full event sequence |
| `event_id` | str | UUID of the spend event |
| `decision` | str | `"AUTHORIZED"`, `"DENIED"`, `"CONFIRMED"`, etc. |
| `requested_quantity` | float | Amount requested |
| `created_at` | str | ISO 8601 timestamp |
| `agent_id` | str | Agent that made the request |
| `claimed_category` | str | Category label if provided |
| `description` | str | Human-readable label |
| `millis_since_previous` | int | Ms elapsed since the prior event (0 for the first) |

---

## Full replay with per-event snapshots

`GET /api/v1/budgets/{id}/replay` replays every event and returns the projected budget state after each one. Use this when you want to understand the step-by-step accounting — not just the final numbers, but how the balance changed with each event.

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

## `replay_counterfactual` — what-if analysis

Answers: *"If I had set the hotel allocation to $400, how many fewer denials would there have been?"*

```python
# Python SDK
result = client.replay_counterfactual(
    budget_id="bdg_abc",
    hypothetical_policy={
        "total_limit": 500,
        "allocations": [
            {"category": "hotels", "limit": 400, "enforcement_mode": "CATEGORY_CONSTRAINED"},
        ],
        "max_transaction_quantity": 250,
    },
)

actual = result["actualPolicySummary"]
hypo = result["hypotheticalPolicySummary"]
print(f"Actual denials:       {actual['deniedCount']}")
print(f"Hypothetical denials: {hypo['deniedCount']}")
print(f"Delta:                {hypo['additionalDenials']} more / {hypo['fewerDenials']} fewer")
```

Via REST:

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

**Post-incident use:** If an agent run caused unexpected spend, use counterfactual replay to test what velocity controls or allocation limits would have intercepted it at which specific event. The `changed: true` entries are your investigation starting points.

---

## Spend tree

For fleet budgets, the ledger is organized as a tree: the orchestrator budget at the root, delegation tokens as branches, and individual authorization events as leaves.

```bash
curl -H "X-Agent-Budget-Key: $KEY" \
  "http://localhost:8080/api/v1/budgets/$FLEET_BUDGET_ID/spend-tree"
```

The response shows which sub-agent authorized what, which events were confirmed vs voided, and the rolled-up totals at each level.

---

## Worked example: "How did the agent overspend?"

An agent session burned through a $500 budget unexpectedly. Use the timeline and state methods together to find exactly what happened.

**Step 1 — Get the timeline to find the sequence**

```python
timeline = client.get_budget_timeline(
    budget_id="bdg_abc",
    from_time=datetime(2025, 5, 28, 13, 0, tzinfo=timezone.utc),
    until=datetime(2025, 5, 28, 15, 0, tzinfo=timezone.utc),
)

for event in timeline.timeline:
    print(
        f"#{event.event_index:>3}  {event.created_at}  "
        f"{event.decision:12s}  ${event.requested_quantity:>8.2f}  "
        f"{event.claimed_category or '-':12s}  {event.description}"
    )
```

Output:
```
#  0  2025-05-28T13:02:15Z  AUTHORIZED    $  150.00  flights       NYC → LAX flight
#  1  2025-05-28T13:47:30Z  AUTHORIZED    $  200.00  hotels        Hotel Night 1
#  2  2025-05-28T14:12:05Z  AUTHORIZED    $  120.00  hotels        Hotel Night 2
#  3  2025-05-28T14:31:58Z  AUTHORIZED    $   89.00  misc          Airport transfer
#  4  2025-05-28T14:32:01Z  DENIED        $   75.00  flights       Return flight
```

The budget hit its limit between events #3 and #4 — a 3-second window around 14:32.

**Step 2 — Project the balance at the exact moment**

```python
snapshot = client.get_budget_state_at(
    budget_id="bdg_abc",
    at=datetime(2025, 5, 28, 14, 32, 0, tzinfo=timezone.utc),
)

print(f"Events applied: {snapshot.events_applied}")   # 4
print(f"Spent:    ${snapshot.quantity_spent:.2f}")     # $559.00 (150+200+120+89)
print(f"Reserved: ${snapshot.quantity_reserved:.2f}")  # $0.00 (nothing pending)
print(f"Available: ${snapshot.available:.2f}")         # $0.00 — already exhausted
```

**Step 3 — Check allocation breakdowns**

```python
for alloc in snapshot.allocations:
    print(f"{alloc.category:12s}  spent={alloc.quantity_spent:.2f}  reserved={alloc.quantity_reserved:.2f}  available={alloc.available:.2f}")
```

Output:
```
flights       spent=150.00  reserved=0.00   available=150.00
hotels        spent=0.00    reserved=320.00  available=-120.00
misc          spent=0.00    reserved=89.00   available=-89.00
```

Both hotel and misc allocations are over their per-category limits. The hotels allocation has $320 reserved against a $200 limit — two unconfirmed authorizations. The agent should have confirmed or voided hotel reservations before authorizing more spend.

---

## Async Python

All SDK methods are available on `AsyncFiGuardClient` with the same signatures:

```python
async with AsyncFiGuardClient() as client:
    snapshot = await client.get_budget_state_at(budget_id="bdg_...", at="2025-05-28T14:32:00Z")
    timeline = await client.get_budget_timeline(budget_id="bdg_...", from_time="2025-05-28T13:00:00Z")
```
