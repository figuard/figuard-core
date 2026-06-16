# Handling Denials

When FiGuard denies an authorization, your agent receives a structured denial reason instead of a boolean. This guide explains every denial code, what causes it, how each framework surfaces it to the LLM, and how to write prompt instructions that let the LLM reason about and recover from denials.

Use `DenialReason` constants instead of raw strings — you get IDE autocomplete and typo protection:

```python
# Python
from figuard import DenialReason

if result.denial_reason == DenialReason.BUDGET_EXHAUSTED: ...
if result.denial_reason == DenialReason.ALLOCATION_EXHAUSTED: ...
```

```typescript
// TypeScript
import { DenialReason } from "figuard";

if (result.denialReason === DenialReason.BUDGET_EXHAUSTED) { ... }
if (result.denialReason === DenialReason.ALLOCATION_EXHAUSTED) { ... }
```

---

## Denial reason codes

> For the complete denial code reference — all 29 codes with trigger conditions and technical details — see [Enforcement Features](enforcement.md#denial-codes). The table below focuses on recoverability and what your application should do.

| Code | Cause | Recoverable? |
|---|---|---|
| `BUDGET_EXHAUSTED` | Total budget has no remaining capacity | Only by increasing the budget limit |
| `BUDGET_EXPIRED` | Budget has passed its expiry time | No — create a new budget |
| `BUDGET_PAUSED` | Budget was manually paused or paused by anomaly detection | Yes — resume with override |
| `BUDGET_CANCELLED` | Budget was cancelled | No — create a new budget |
| `ALLOCATION_EXHAUSTED` | Category allocation has no remaining capacity | Try a different category, or reallocate |
| `VELOCITY_LIMIT_EXCEEDED` | Too many requests in the velocity window | Retry after the window resets |
| `ENTITY_ALREADY_AUTHORIZED` | Same `entity_id` already has an active reservation | Void the original, or confirm and proceed |
| `INVALID_SESSION_TOKEN` | Token doesn't exist, is expired, or belongs to a different tenant | Verify the token; re-create the budget if needed |
| `SUBTREE_CAP_EXCEEDED` | Causal chain total exceeds the `max_subtree_quantity` set on the root event | The job is over budget — abort or request a cap increase |
| `MISSING_CLAIMED_CATEGORY` | `claimed_category` is required in STRICT/CATEGORY_CONSTRAINED mode but was not provided | Add `claimed_category` to the authorize call |
| `SUBSCRIPTION_PAUSED` | The subscription linked to this budget is paused | Resume the subscription |

---

## How each framework surfaces denials

### LangChain (`FiGuardCallbackHandler`)

The callback handler returns the denial as the tool result string. The LLM receives it as tool output and continues the chain:

```
Tool result: "FiGuard DENIED: BUDGET_EXHAUSTED — $0.00 remaining, $150.00 requested"
```

The agent loop continues — the LLM sees the denial string and reasons about next steps. No exception is raised.

### CrewAI (`FiGuardCrewGuard`)

Same as LangChain — the denial string is returned as the tool result. CrewAI's agent loop receives it as a task output and can adjust its plan.

### OpenAI Agents SDK (`@guarded_function_tool`)

The tool function never runs. The denial string is returned as the function result, which the model sees in its tool call response:

```json
{
  "role": "tool",
  "content": "FiGuard DENIED: ALLOCATION_EXHAUSTED — flights: $600 limit, $600 spent, $0 remaining"
}
```

### Raw client

```python
from figuard import DenialReason

result = client.authorize(...)
if not result.is_authorized:
    # result.denial_reason — use DenialReason constants for comparison
    # result.denial_message — human-readable description
    if result.denial_reason == DenialReason.BUDGET_EXHAUSTED:
        print("Budget exhausted:", result.denial_message)
```

Or use `raise_if_denied()` for exception-driven flow:

```python
result = client.authorize(...).raise_if_denied()
# Raises FiGuardDeniedException if denied; returns result if authorized
```

---

## Writing LLM prompt instructions for denials

The most important thing: **tell the LLM what denial means and what to do about it**. Without instructions, models will hallucinate fallback behavior or loop indefinitely.

### Minimal system prompt addition

```
When a tool returns a string starting with "FiGuard DENIED:", do not retry the same call.
Read the denial reason and adjust your plan:
- BUDGET_EXHAUSTED or ALLOCATION_EXHAUSTED: inform the user and stop attempting that category
- VELOCITY_LIMIT_EXCEEDED: wait and try again, or inform the user
- BUDGET_EXPIRED or BUDGET_CANCELLED: stop — the session is no longer valid
- ENTITY_ALREADY_AUTHORIZED: the action is already pending — do not duplicate it
For any denial, report the exact reason to the user in plain language.
```

### Per-framework examples

**LangChain system prompt:**
```python
prompt = ChatPromptTemplate.from_messages([
    ("system", """You are a travel booking assistant with a spending budget.

If a tool returns 'FiGuard DENIED: BUDGET_EXHAUSTED', stop booking and tell the user
you've reached the spending limit for this session.

If a tool returns 'FiGuard DENIED: ALLOCATION_EXHAUSTED', the specific category
(flights, hotels, etc.) is at its limit — offer cheaper alternatives in that category
or suggest using a different category.

Never retry a denied action with the same parameters. Always explain the denial to the user."""),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])
```

**OpenAI Agents SDK instructions:**
```python
agent = Agent(
    name="travel_agent",
    instructions="""You are a travel booking assistant.

When a booking tool returns a FiGuard denial:
- BUDGET_EXHAUSTED: "I've reached the spending limit. Current session budget is exhausted."
- ALLOCATION_EXHAUSTED: "The [category] budget is fully used. I can try a cheaper option."
- VELOCITY_LIMIT_EXCEEDED: "Too many requests — please wait a moment and I'll retry."
- ENTITY_ALREADY_AUTHORIZED: "That booking is already pending confirmation."

Always relay the exact denial reason to the user.""",
    tools=[book_flight, book_hotel],
)
```

---

## Handling specific codes

### `BUDGET_EXHAUSTED`

The session is over budget. The right response depends on the workflow:

```python
result = client.authorize(session_token=token, requested_quantity=amount, ...)
if not result.is_authorized and result.denial_reason == DenialReason.BUDGET_EXHAUSTED:
    # Option 1: hard stop
    raise BudgetExceededError(f"Agent session exhausted. Spent: {budget.quantity_spent}")

    # Option 2: request approval (via webhook / human-in-the-loop)
    request_budget_increase(budget_id=budget.id, requested_increase=amount)
```

### `ALLOCATION_EXHAUSTED`

Only the named category is exhausted — the budget may still have funds. The LLM can try a different category or offer a cheaper option:

```python
if result.denial_reason == DenialReason.ALLOCATION_EXHAUSTED:
    remaining = {
        alloc.category: alloc.available_quantity
        for alloc in client.get_budget(budget_id).allocations
    }
    # Pass remaining to the LLM so it can choose the least-constrained category
```

### `VELOCITY_LIMIT_EXCEEDED`

The velocity window will reset. Safe to retry after the window expires:

```python
import time

result = client.authorize(...)
if result.denial_reason == DenialReason.VELOCITY_LIMIT_EXCEEDED:
    time.sleep(60)  # or read velocity_max_per_minute from budget config
    result = client.authorize(...)  # retry with a fresh idempotency key
```

### `ENTITY_ALREADY_AUTHORIZED`

The `entity_id` you passed is already reserved. FiGuard prevents double-authorization of the same entity. The original event id is in `result.original_event_id`:

```python
if result.denial_reason == DenialReason.ENTITY_ALREADY_AUTHORIZED:
    # The entity is already authorized — confirm or void the original
    existing = result.original_event_id
    if action_actually_happened:
        client.confirm_event(event_id=existing, confirmed_quantity=actual_amount)
    else:
        client.void_event(event_id=existing, reason="DUPLICATE_REQUEST")
```

### `BUDGET_PAUSED`

The budget was paused — either manually or by anomaly detection. It can be resumed with an override reason:

```python
if result.denial_reason == DenialReason.BUDGET_PAUSED:
    # Alert the operator; resume only after human review
    notify_ops(budget_id=budget.id, reason="budget_paused_by_anomaly")
    # After review:
    client.resume_budget(budget_id=budget.id, override_reason="reviewed_by_ops")
```

---

## Testing denial handling

Use `dry_run=True` to trigger the authorization logic without writing to the ledger — useful for testing your denial handling code without consuming budget:

```python
result = client.authorize(
    session_token=token,
    agent_id="test_agent",
    action_type="PURCHASE",
    description="Test denial handling",
    requested_quantity=999_999,  # more than any budget has
    dry_run=True,
)
assert result.denial_reason == DenialReason.BUDGET_EXHAUSTED
# dry_run=True: nothing written to ledger, budget unaffected
```
