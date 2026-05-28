# Audit & Replay

FiGuard's replay API lets you reconstruct exactly what happened inside a budget at any point in the past. Every authorized and denied event is stored in the ledger in order, so you can replay the full sequence, project state to a specific timestamp, or ask counterfactual questions about policy changes.

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

**Response shape (`BudgetStateSnapshot`)**

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
| `allocations` | list | Per-category breakdown (see `ReplayAllocationState`) |

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

console.log(`${tl.totalEvents} events in window`);
for (const e of tl.timeline) {
  const gap = e.millisSincePrevious != null
    ? `+${(e.millisSincePrevious / 1000).toFixed(1)}s`
    : "start";
  console.log(`  [${gap.padStart(8)}] #${e.eventIndex}  ${e.decision.padEnd(12)}  ${e.requestedQuantity}  ${e.agentId}`);
}
```

**Response shape (`BudgetTimeline`)**

| Field | Type | Description |
|---|---|---|
| `budget_id` | str | Budget ID |
| `total_events` | int | Total events in the window |
| `timeline` | list | Ordered list of `TimelineEvent` rows |

**`TimelineEvent` fields**

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

## Worked example: "How did the agent overspend?"

An agent session burned through a $500 budget unexpectedly. The support ticket says it happened "sometime around 2 PM". Use the two methods together to find exactly what happened.

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
# Project just before event #4
snapshot = client.get_budget_state_at(
    budget_id="bdg_abc",
    at=datetime(2025, 5, 28, 14, 32, 0, tzinfo=timezone.utc),
)

print(f"Events applied: {snapshot.events_applied}")   # 4
print(f"Spent:    ${snapshot.quantity_spent:.2f}")     # $559.00 (150+200+120+89)
print(f"Reserved: ${snapshot.quantity_reserved:.2f}")  # $0.00 (nothing pending)
print(f"Available: ${snapshot.available:.2f}")         # $0.00 — already exhausted
```

The agent authorized 4 reservations totalling $559 — $59 over the $500 limit — before the 5th call was denied. The hotel and misc reservations were not confirmed in time to release capacity for the return flight.

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

Both hotel and misc allocations are over their per-category limits. The hotels allocation has $320 reserved (two unconfirmed authorizations) against a $200 limit — this is the root cause. The agent should have confirmed or voided hotel reservations before authorizing more spend.

---

## `replay_counterfactual` — what-if analysis

Answers: *"If I had set the hotel allocation to $400, how many fewer denials would there have been?"*

```python
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

See the full `replayCounterfactual` / `replay_counterfactual` docstring for the complete request and response schema.

---

## Async Python

All three methods are available on `AsyncFiGuardClient` with the same signatures:

```python
async with AsyncFiGuardClient() as client:
    snapshot = await client.get_budget_state_at(budget_id="bdg_...", at="2025-05-28T14:32:00Z")
    timeline = await client.get_budget_timeline(budget_id="bdg_...", from_time="2025-05-28T13:00:00Z")
```
