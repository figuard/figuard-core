# Removing FiGuard

This document explains what it takes to remove FiGuard from your codebase if you decide it's not right for you. Read it before you integrate — knowing the exit path up front is how you make an informed decision.

**Short version:** FiGuard touches three things — a client import, a budget-creation call, and a pair of `authorize` / `confirm` calls around each agent action. Removing it means deleting those calls and the budget setup. Your agent logic doesn't change.

---

## What FiGuard touches in your code

FiGuard is wired into your code in three places:

**1. Budget creation** (typically once per user session or task)
```python
budget = client.create_budget(user_id=..., total_limit=500, currency="USD", expires_in="24h")
session_token = budget.primary_token.session_token
```

**2. Pre-action authorization** (once per agent action)
```python
result = client.authorize(session_token=..., agent_id=..., action_type=...,
                          description=..., requested_quantity=...)
if not result.is_authorized:
    raise DeniedError(result.denial_reason)
```

**3. Post-action lifecycle** (confirm on success, fail or void on error)
```python
client.confirm_event(event_id=result.event_id, confirmed_quantity=actual_amount)
# or
client.fail_event(event_id=result.event_id, reason="EXECUTION_FAILED")
```

None of these are in your agent's core logic — they're guards around agent actions.

---

## Minimal removal

Delete the guard calls and carry the session token as a plain variable (or remove it entirely). Your agent logic — the actual API calls, tool invocations, LLM prompts — is unchanged.

**Before:**
```python
budget = client.create_budget(user_id=user_id, total_limit=500, currency="USD", expires_in="24h")
token = budget.primary_token.session_token

result = client.authorize(
    session_token=token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Book NYC–LAX",
    requested_quantity=299,
)
if not result.is_authorized:
    raise DeniedError(result.denial_reason)

charge = payment_api.charge(user_id=user_id, amount=299)

client.confirm_event(event_id=result.event_id, confirmed_quantity=charge.amount)
```

**After:**
```python
charge = payment_api.charge(user_id=user_id, amount=299)
```

Three lines of guard code removed. The `payment_api.charge` call is untouched.

---

## Framework integration removal

### LangChain (callback handler)

If you added FiGuard via `auto_guard_langchain`:
```python
# Remove this line
executor = auto_guard_langchain(executor, budget=500, currency="USD")
```
LangChain's executor continues working normally — callbacks are optional.

If you wired `FiGuardCallbackHandler` manually:
```python
# Remove this
handler = FiGuardCallbackHandler(client=client, session_token=token, agent_id="agent")
executor.callbacks = [handler]
```

### CrewAI (tool guard)

If you used `auto_guard_crewai`:
```python
# Remove this
guard = auto_guard_crewai(book_flight_tool, budget=500, currency="USD")
# Pass original tool directly to Agent instead of guard
```

If you wrapped `FiGuardCrewGuard` manually, replace the guard with the original tool in your `Agent(tools=[...])` list.

### MCP (Model Context Protocol)

If you ran the FiGuard MCP server alongside your Claude Desktop / Claude Code setup, remove `figuard-mcp` from your MCP server list in Claude Desktop settings. The tools (`authorize`, `get_budget`, etc.) will no longer appear — no other side effects.

---

## Your data

FiGuard stores:
- **Budget records** — your configured limits and category allocations
- **Spend event ledger** — every authorization, confirmation, failure, and void
- **Webhook delivery logs** — if you configured webhooks

**Exporting your data** before removal:

```python
# Page through the full ledger for a budget
page = client.get_ledger(budget_id=budget_id, size=100)
events = list(page.events)
while page.has_next:
    page = client.get_ledger(budget_id=budget_id, page=page.page + 1, size=100)
    events.extend(page.events)

# Write to CSV or whatever suits your audit requirements
import csv
with open("figuard_export.csv", "w") as f:
    writer = csv.DictWriter(f, fieldnames=["id", "decision", "agentId", "actionType",
                                            "requestedQuantity", "confirmedQuantity", "createdAt"])
    writer.writeheader()
    for e in events:
        writer.writerow({"id": e.id, "decision": e.decision, "agentId": e.agent_id, ...})
```

If you're self-hosting, export directly from the `spend_events` table:
```sql
COPY (
  SELECT e.*, b.user_id, b.currency, b.total_limit
  FROM spend_events e
  JOIN agent_budgets b ON e.budget_id = b.id
  WHERE b.tenant_id = '<your-tenant-id>'
  ORDER BY e.created_at
) TO '/tmp/figuard_export.csv' WITH CSV HEADER;
```

---

## What you lose

Removing FiGuard means:
- No per-agent spend limits — agents can run without financial ceilings
- No idempotency enforcement — retry loops can double-charge
- No audit trail — you won't know which agent authorized what spend or when
- No anomaly detection — cost spikes won't surface until you see the invoice

If your concern is **operational burden** rather than the features themselves, consider [shadow mode](shadow-mode.md) — it gives you the audit trail with zero enforcement overhead. Or use `fail_open=True` so FiGuard downtime doesn't affect agent availability. See [FAILURE_BEHAVIOR.md](../FAILURE_BEHAVIOR.md).

---

## Switching to a different solution

If you're evaluating alternatives, the relevant integration surface is:
- **Pre-action hook** — something that runs before each agent tool call with the agent ID and estimated cost
- **Post-action hook** — updates the record with the actual cost after the action completes
- **Per-session ceiling** — a configurable limit that stops the agent when the budget is exhausted

FiGuard's data model maps directly to that interface. Any system that implements these three things can be a drop-in replacement at the code level.

---

## Summary

| What you added | How to remove |
|---|---|
| `create_budget()` call | Delete it |
| `authorize()` + `confirm/fail/void` calls | Delete them — your action call stays |
| `FiGuardCallbackHandler` on executor | Remove from `executor.callbacks` |
| `FiGuardCrewGuard` wrapping a tool | Replace guard with original tool in Agent |
| `figuard-mcp` in MCP config | Remove from MCP server list |
| Self-hosted FiGuard service | Stop the container; drop the database if desired |

Removal is a mechanical find-and-delete. No migration scripts. No schema changes in your application. No vendor lock-in beyond the time you spent integrating.
