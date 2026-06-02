# Cookbook

Short, focused recipes for common FiGuard patterns. Each one solves one problem in the minimum amount of code.

---

## Authorization patterns

### 1. Authorize → confirm → void on failure

The core 3-step pattern with correct error handling. Always void on failure so the reservation doesn't dangle.

```python
auth = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="NYC flight",
    requested_quantity=299.00,
    idempotency_key="booking-sfo-jfk-001",
).raise_if_denied()  # raises FiGuardDeniedException if denied

try:
    result = stripe.charge(299.00)
    client.confirm_event(auth.event_id, confirmed_quantity=result.amount_charged)
except Exception as e:
    client.void_event(auth.event_id, reason="PAYMENT_FAILED")
    raise
```

**Why void on failure:** the authorization reserves capacity. If you don't void it, that capacity is locked until the budget expires. Unconfirmed events don't count as spent — they count as reserved. Void releases it immediately.

---

### 2. Parallel tool calls without double-spending

Two concurrent tool calls can both read the same available balance and both get approved. Use a stable idempotency key derived from the request content, not a random UUID, so a retry finds the original result instead of creating a second reservation.

> **Anti-pattern:** `idempotency_key=str(uuid.uuid4())` generates a fresh key on every call — a retry creates a duplicate reservation. Use a key derived from the request content (see `stable_key` below) so retries are idempotent.

```python
import hashlib

def stable_key(agent_id: str, description: str, amount: float) -> str:
    payload = f"{agent_id}:{description}:{amount}"
    return hashlib.sha256(payload.encode()).hexdigest()[:32]

auth = client.authorize(
    session_token=token,
    agent_id="research_agent",
    action_type="API_CALL",
    description="Fetch market data for AAPL",
    requested_quantity=0.02,
    idempotency_key=stable_key("research_agent", "Fetch market data for AAPL", 0.02),
)
```

**Why this works:** if the agent retries (network timeout, retry loop), the same key returns the original `event_id` and `decision`. A second authorization is never created, so capacity is never double-reserved.

---

### 3. Causal chain — sub-agent spend under a parent event

Pass `parent_event_id` when a sub-agent's spend is caused by an orchestrator action. This links events in the spend tree and enables `void_tree` to cascade.

```python
# Orchestrator authorizes the top-level task
orchestrator_auth = client.authorize(
    session_token=fleet_token,
    agent_id="orchestrator",
    action_type="TASK",
    description="Research AAPL earnings",
    requested_quantity=5.00,
    idempotency_key="task-aapl-001",
)

# Sub-agent authorizes each action, linking back to the parent
sub_auth = client.authorize(
    session_token=delegation_token,
    agent_id="research_sub_agent",
    action_type="LLM_CALL",
    description="Summarize 10-K filing",
    requested_quantity=0.80,
    idempotency_key="llm-aapl-summary-001",
    parent_event_id=orchestrator_auth.event_id,  # links to parent
)

# If the task fails, void the entire subtree in one call
client.void_tree(orchestrator_auth.event_id, reason="TASK_CANCELLED")
# Voids orchestrator_auth + all linked sub_auth events atomically
```

---

## Budget management

### 4. Soft alert at 80%, hard stop at 100%

Register a webhook for `BUDGET_90_PCT` to warn your orchestrator before the budget runs out, and rely on `BUDGET_EXHAUSTED` denials as the hard stop.

```python
# Register once when the budget is created
client.create_webhook(
    url="https://yourapp.example.com/webhooks/figuard",
    secret="whsec_...",
    events=["BUDGET_90_PCT", "BUDGET_EXHAUSTED", "ANOMALY_DETECTED"],
)

# In your webhook handler
@app.post("/webhooks/figuard")
def handle():
    event = FiGuardClient.verify_webhook(request.get_data(), request.headers["X-Webhook-Signature"], SECRET)

    if event["eventType"] == "BUDGET_90_PCT":
        # Warn — still running, but start wrapping up
        notify_orchestrator("Budget at 90% — begin graceful shutdown")

    elif event["eventType"] == "BUDGET_EXHAUSTED":
        # Hard stop — next authorize call will be denied anyway
        stop_orchestrator(event["budgetId"])

    return "", 204
```

---

### 5. Graceful shutdown when budget is nearly exhausted

Check `available_quantity` before each step to initiate a clean shutdown rather than hitting a mid-step denial.

```python
MIN_RESERVE = 50.00  # keep $50 in reserve for cleanup steps

def can_proceed(budget_id: str, next_step_cost: float) -> bool:
    budget = client.get_budget(budget_id)
    return budget.available_quantity - next_step_cost >= MIN_RESERVE

# In the agent loop
for step in planned_steps:
    if not can_proceed(budget.id, step.estimated_cost):
        client.void_tree(current_event_id, reason="GRACEFUL_SHUTDOWN")
        return summarize_partial_results()

    auth = client.authorize(..., requested_quantity=step.estimated_cost, ...)
    # run step...
```

**When to use:** long-running agents (research loops, batch processors) where you want to return partial results cleanly rather than abruptly stopping mid-task.

---

## Testing

### 6. Unit testing an agent with MockFiGuardClient

Swap in `MockFiGuardClient` to test authorization logic in-memory with no network calls.

```python
from figuard.testing import MockFiGuardClient
from figuard import DenialReason

def test_agent_stops_when_budget_exhausted():
    client = MockFiGuardClient(total_limit=300, currency="USD")

    # First booking fits
    r1 = client.authorize(
        session_token=client.sandbox_token,
        agent_id="travel_agent", action_type="PURCHASE",
        description="Flight", requested_quantity=250, idempotency_key="k1",
    )
    assert r1.is_authorized
    client.confirm_event(r1.event_id, confirmed_quantity=250)

    # Second booking exceeds remaining $50
    r2 = client.authorize(
        session_token=client.sandbox_token,
        agent_id="travel_agent", action_type="PURCHASE",
        description="Hotel", requested_quantity=200, idempotency_key="k2",
    )
    assert r2.denial_reason == DenialReason.BUDGET_EXHAUSTED

    client.assert_authorized(count=1)
    client.assert_denied(reason=DenialReason.BUDGET_EXHAUSTED, count=1)
    client.assert_spent(250)
```

**Tip:** pass `MockFiGuardClient` wherever your agent accepts a `FiGuardClient`. It implements the same method signatures — no interface changes needed.

---

## Observability

### 7. FiGuard spans in Langfuse / Jaeger

FiGuard emits `figuard.authorize`, `figuard.confirm`, `figuard.fail`, and `figuard.void_tree` spans via OpenTelemetry. They appear as child spans under your LangChain tool spans — so every authorization decision is visible in the same trace as the LLM call that triggered it.

→ Full setup for Langfuse, Jaeger, Honeycomb, Datadog, and TypeScript — see [Observability](integrations/observability.md).

---

### 9. Debugging a denied event

An agent was denied mid-session and you're not sure why. Use `get_budget_timeline` to find the sequence, then `get_budget_state_at` to confirm the balance at that moment.

```python
from datetime import datetime, timezone

# Step 1 — see the full event sequence
timeline = client.get_budget_timeline(
    budget_id="bdg_...",
    from_time=datetime(2025, 5, 28, 13, 0, tzinfo=timezone.utc),
)
for e in timeline.timeline:
    gap = f"+{e.millis_since_previous/1000:.1f}s" if e.millis_since_previous else "start"
    print(f"[{gap:>8}] {e.decision:12s} ${e.requested_quantity:>8.2f}  {e.description}")

# Output shows: ... AUTHORIZED $200 ... AUTHORIZED $150 ... DENIED $200 ...

# Step 2 — project the balance just before the denial
snapshot = client.get_budget_state_at(
    budget_id="bdg_...",
    at="2025-05-28T14:31:58Z",  # timestamp from the timeline
)
print(f"Available at denial time: ${snapshot.available:.2f}")
print(f"Events applied: {snapshot.events_applied}")
```

---

### 10. Export all spend for a session with iter_events

Use `iter_events` to page through the full ledger without manually tracking page numbers.

```python
# Iterate all events for a budget — auto-paginates
total_confirmed = 0.0
for event in client.iter_events(budget_id="bdg_..."):
    if event.decision == "CONFIRMED":
        total_confirmed += event.confirmed_quantity or event.requested_quantity
        print(f"{event.created_at}  {event.agent_id:20s}  ${event.confirmed_quantity:.2f}  {event.description}")

print(f"\nTotal confirmed spend: ${total_confirmed:.2f}")

# Filter to denied events only
denied = list(client.iter_events(budget_id="bdg_...", decision="DENIED"))
print(f"{len(denied)} denied events")
```

**Async version:**

```python
async for event in client.iter_events(budget_id="bdg_..."):
    process(event)
```
