# Shadow Mode: Observe Before You Enforce

Shadow mode lets you run FiGuard in your production pipeline **without blocking any agent actions**. You get the full audit trail, anomaly detection signals, and budget utilization data — but every authorization returns AUTHORIZED regardless of limit.

Use it to build confidence before flipping enforcement on.

---

## The problem shadow mode solves

Enforcing spend limits on an agent you've never instrumented before is risky. You don't know:

- What a typical transaction looks like in production traffic
- Which agents are making the most requests
- Whether your limit is calibrated correctly (too tight → false denials; too loose → useless)
- Whether your `confirm_event` / `void_event` lifecycle calls are wired correctly

Shadow mode gives you a production data set to calibrate against. You see what *would have* been denied without affecting live agent runs.

---

## How to enable shadow mode

Shadow mode is two budget flags used together:

| Flag | Value | Effect |
|---|---|---|
| `anomaly_detection_enabled` | `True` | FiGuard monitors spend patterns and fires `ANOMALY_DETECTED` webhooks |
| `auto_pause_on_anomaly` | `False` | Anomalies are recorded, but the budget is NOT paused and agents are NOT blocked |

Set both when creating the budget:

```python
from figuard import FiGuardClient

client = FiGuardClient()

budget = client.create_budget(
    user_id="user_123",
    total_limit=10_000,       # Set a generous ceiling — won't be enforced yet
    currency="USD",
    expires_in="30d",
    anomaly_detection_enabled=True,
    auto_pause_on_anomaly=False,   # <-- advisory mode; no enforcement
)
```

```typescript
const client = new FiGuardClient();

const budget = await client.createBudget({
  userId: "user_123",
  totalLimit: 10_000,
  currency: "USD",
  expiresIn: "30d",
  anomalyDetectionEnabled: true,
  autoPauseOnAnomaly: false,   // <-- advisory mode; no enforcement
});
```

Every `authorize()` call returns AUTHORIZED (as long as the budget has capacity), and anomaly events are fired as webhooks without pausing the budget.

---

## What you can observe

Once agents are running with a shadow budget:

**In the dashboard:**
- Full ledger of every agent authorization with agent ID, action type, and spend amount
- Budget utilization chart (how fast capacity is being consumed)
- Anomaly signals (unusual velocity, duplicate entity IDs, burst spending)

**Via the API:**
```python
# Page through the ledger to see all events
page = client.get_ledger(budget_id=budget.id, size=50)
for event in page.events:
    print(event.agent_id, event.action_type, event.requested_quantity, event.decision)

# View the causal spend tree (which agents triggered which child agents)
tree = client.get_spend_tree(budget_id=budget.id)
```

**Via webhooks:**
Set up a webhook endpoint to receive `ANOMALY_DETECTED` events in real time. The payload includes the anomaly type and the triggering event.

---

## Calibrating your limits

After 1–2 weeks of shadow data, you have enough signal to set real limits:

```python
# Get current utilization
b = client.get_budget(budget.id)
print(f"Spent so far: {b.quantity_spent}")
print(f"Daily average: {b.quantity_spent / days_elapsed:.2f}")
```

A common rule of thumb:
- Set `total_limit` to **2–3× your observed daily spend** (gives room for legitimate spikes)
- Set `soft_limit` at **80% of total_limit** for early warning without hard enforcement
- Set `max_transaction_quantity` to **3–5× your observed P95 single-transaction amount** (catches runaway loops)

---

## Graduating to enforcement

When you're ready to turn enforcement on, update the budget:

```python
# Update to enforce — anomalies now pause the budget automatically
client.update_budget(
    budget_id=budget.id,
    total_limit=2_000,                 # calibrated ceiling
    soft_limit=1_600,
    max_transaction_quantity=150,
    auto_pause_on_anomaly=True,        # enforcement is now live
)
```

Or create a new budget with enforcement-on from the start, and cut your agents over to the new session token. This is cleaner if you want a fresh ledger for the production phase.

---

## Shadow mode vs `fail_open`

These serve different purposes:

| | Shadow mode | `fail_open` |
|---|---|---|
| **Purpose** | Observe traffic before enforcing | Keep agents running if FiGuard is unreachable |
| **When server is reachable** | Creates real ledger entries; fires webhooks | Creates real ledger entries normally |
| **When server is unreachable** | N/A (server must be reachable) | Returns synthetic AUTHORIZED; no ledger entry |
| **Use in production** | Yes — during onboarding and calibration | Optional — depends on your failure tolerance |

Use shadow mode to **build confidence before enforcement**.  
Use `fail_open` to **handle FiGuard downtime gracefully**.

See [FAILURE_BEHAVIOR.md](../FAILURE_BEHAVIOR.md) for `fail_open` details.

---

## Recommended rollout sequence

1. **Week 1–2: Shadow mode** — instrument agents, collect data, zero denials
2. **Week 3: Soft limit only** — set `soft_limit`, enable `BUDGET_SOFT_LIMIT_REACHED` webhook alerts, still no hard denials
3. **Week 4+: Full enforcement** — set `total_limit`, `max_transaction_quantity`, `auto_pause_on_anomaly=True`

This three-phase approach means your first enforcement event is never a surprise.
