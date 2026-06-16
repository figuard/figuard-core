# FiGuard Python SDK

[![PyPI version](https://img.shields.io/pypi/v/figuard.svg)](https://pypi.org/project/figuard/)
[![Python](https://img.shields.io/pypi/pyversions/figuard.svg)](https://pypi.org/project/figuard/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/figuard/figuard-core/blob/main/LICENSE)

Pre-flight spend authorization for AI agents. Stop your agent from overspending before it happens.

## Install

```bash
# Core (sync client only)
pip install figuard

# With framework integrations
pip install figuard[langchain]      # LangChain + LangGraph
pip install figuard[crewai]         # CrewAI
pip install figuard[openai]         # OpenAI function calling
pip install figuard[openai-agents]  # OpenAI Agents SDK
pip install figuard[anthropic]      # Anthropic tool_use
pip install figuard[async]          # AsyncFiGuardClient (aiohttp)
pip install figuard[all]            # everything above
```

Requires Python 3.9+.

## Quickstart — zero infra, runs in-process

No server, no database, no account. `FiGuardClient()` runs enforcement **locally**, in your
process, against a SQLite file in your home dir:

```python
from figuard import FiGuardClient, FiGuardDeniedException

fg = FiGuardClient()                       # embedded by default — local, zero infra

# 1. Create a budget
budget = fg.create_budget(user_id="user_123", total_limit=500.00, currency="USD")

# 2. Pre-authorize every spend before it happens
try:
    auth = fg.authorize(budget=budget, amount=299.00).raise_if_denied()

    # 3. Do the real work, then confirm what was actually spent
    payment_processor.charge(299.00)
    fg.confirm(auth, 299.00)

except FiGuardDeniedException as e:
    print(f"Spend denied: {e.denial_reason}")   # INSUFFICIENT_FUNDS, BUDGET_PAUSED, ...
```

That's the whole reserve → confirm loop — and it ran with no server.

### Graduate to a shared server

When several agents/processes need to share **one** budget, point the *same* client at a
FiGuard server. Same calls, one config change:

```python
fg = FiGuardClient(api_key="fg_live_...", base_url="https://figuard.mycompany.internal")
```

`fg.backend` is `"embedded"`, `"server"`, or `"sandbox"`, and it's logged on startup so you
always know where enforcement runs. To try the shared hosted demo (public, wiped
periodically): `FiGuardClient(mode="sandbox")`.

> The verbose form still works unchanged — `authorize(session_token=…, agent_id=…,
> action_type=…, description=…, requested_quantity=…)` and `confirm_event(event_id, …)` —
> use it when you want richer audit metadata or a scoped session token per agent.

### Framework integrations — one line

```python
from figuard import auto_guard_langchain
executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)

from figuard import auto_guard_crewai
auto_guard_crewai(book_flight_tool, budget=500, velocity_max_per_minute=10)
```

These use the default client (embedded) unless you set `FIGUARD_API_KEY` + `FIGUARD_BASE_URL`
(or `mode="sandbox"`) to point them at a server.

## Async (LangChain / CrewAI / OpenAI Agents)

```python
import asyncio
from figuard import AsyncFiGuardClient

async def run_agent():
    async with AsyncFiGuardClient(api_key="fg_live_...") as client:
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            expires_in="24h",
        )

        result = await client.authorize(
            session_token=budget.primary_token.session_token,
            agent_id="langchain_agent",
            action_type="PURCHASE",
            description="Hotel booking",
            requested_quantity=189.00,
            idempotency_key="hotel-booking-001",
        )

        if result.is_authorized:
            await client.confirm_event(result.event_id, confirmed_quantity=189.00)
```

## Allocation-based budgets

Allocations let you ring-fence spend by category and enforce item-type rules:

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    expires_in="24h",
    allocations=[
        {
            "category": "flights",
            "allowedCategories": ["flight", "airline"],
            "limit": 300.00,
            "enforcementMode": "STRICT",
            "forbiddenItemTypes": ["gift_card", "upgrade"],
        },
        {
            "category": "hotels",
            "allowedCategories": ["hotel", "accommodation"],
            "limit": 200.00,
            "enforcementMode": "CATEGORY_CONSTRAINED",
        },
    ],
)

# claimedCategory must match one of allowedCategories
result = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Flight to NYC",
    requested_quantity=250.00,
    idempotency_key="flight-nyc-001",
    claimed_category="flight",
    claimed_item_type="economy_ticket",
)
```

## Payment lifecycle

```python
# Authorize reserves funds — money has not moved yet
result = client.authorize(...).raise_if_denied()

# Confirm when payment succeeds — finalizes the spend
client.confirm_event(result.event_id, confirmed_quantity=249.00)

# Fail when the payment processor declines — releases the reservation
client.fail_event(result.event_id, reason="PAYMENT_DECLINED")

# Void if the action is cancelled before payment
client.void_event(result.event_id, reason="USER_CANCELLED")
```

## Anomaly detection

Enable per-budget anomaly detection to auto-pause budgets when a single request is statistically unusual:

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=2000.00,
    expires_in="24h",
    anomaly_detection_enabled=True,
    # optional: dedicated URL for anomaly alerts
    # anomaly_alert_webhook_url="https://your-service.com/alerts",
)
```

When a request exceeds `mean × multiplier` (default 3×) and at least 5 prior transactions exist, the budget is auto-paused and an `ANOMALY_DETECTED` webhook fires. Resume after review:

```python
budget = client.resume_budget(
    budget_id,
    override_reason="Reviewed — legitimate bulk purchase",
    override_by="ops-team",
)
```

## Delegation tokens (multi-agent fleets)

Delegate a capped slice of a fleet budget to a sub-agent. The sub-agent gets its own `session_token` scoped to specific categories and limits — it cannot exceed its caps even if the parent budget still has funds.

```python
# Parent agent creates the fleet budget
fleet_budget = client.create_budget(
    user_id="user_123",
    total_limit=2000.00,
    expires_in="8h",
    allocations=[
        {"category": "flights", "limit": 1000.00},
        {"category": "hotels", "limit": 1000.00},
    ],
)

# Issue a scoped token for a sub-agent (e.g. a flight-booking specialist)
token = client.create_delegation_token(
    budget_id=fleet_budget.id,
    label="flight-agent",
    caps=[{"category": "flights", "limit": 300.00}],
)
# Hand token.session_token to the sub-agent
flight_agent_token = token.session_token

# Sub-agent authorizes against its cap — cannot exceed $300 flights
result = sub_client.authorize(
    session_token=flight_agent_token,
    agent_id="flight-specialist",
    action_type="PURCHASE",
    description="NYC flight",
    requested_quantity=250.00,
    idempotency_key="flight-nyc-001",
    claimed_category="flights",
).raise_if_denied()

# Revoke at any time
client.revoke_delegation_token(token.id)
```

## Error handling

```python
from figuard import (
    FiGuardDeniedException,   # decision == DENIED (not an HTTP error)
    FiGuardApiError,          # 4xx / 5xx from the API
    FiGuardConnectionError,   # network failure after all retries
)

try:
    result = client.authorize(...).raise_if_denied()
except FiGuardDeniedException as e:
    print(e.denial_reason)    # e.g. "INSUFFICIENT_FUNDS"
    print(e.denial_message)   # human-readable explanation
    # if denial_reason == "ENTITY_ALREADY_AUTHORIZED":
    #   e.original_event_id   # UUID of the existing event
except FiGuardApiError as e:
    print(e.status_code, e.message)
except FiGuardConnectionError as e:
    print("Network failure:", e)
```

The SDK automatically retries 5xx responses up to 3 times with exponential backoff (1s, 2s, 4s). 4xx errors are never retried.

## Ledger and reporting

```python
# Paginated spend history
page = client.get_ledger(budget_id, page=0, size=20, decision="CONFIRMED")
for event in page.events:
    print(event.id, event.decision, event.confirmed_quantity)

# Causal spend tree (which agent triggered which spend)
tree = client.get_spend_tree(budget_id)
for root in tree.roots:
    print(root.event.agent_id, len(root.children), "child events")
```

## Configuration

```python
client = FiGuardClient(
    api_key="fg_live_...",
    base_url="https://your-figuard.example.com",  # your self-hosted instance
    timeout=30,                          # per-request timeout in seconds
)
```

## Security notes

- The raw `session_token` is returned **once** on `create_budget()` and never again. Store it securely — treat it like a password.
- The SDK logs only the first 8 characters of the session token. The full token never appears in logs.
- `idempotency_key` is optional — a UUID is auto-generated if omitted. Provide a stable key per logical spend intent so retries collapse to the same event instead of creating duplicates.

---

## Framework integrations

Each integration is an optional extra. Install only what you need.

### LangChain / LangGraph

```bash
pip install figuard[langchain]
```

```python
from figuard.integrations.langchain import FiGuardCallbackHandler, FiGuardToolGuard

# Option A — callback handler: guards every tool in an AgentExecutor
executor = AgentExecutor(
    agent=agent,
    tools=tools,
    handle_tool_error=True,   # required — sends denial to the LLM
    callbacks=[FiGuardCallbackHandler(
        client=client,
        session_token=budget.primary_token.session_token,
        tool_category_map={"book_flight": "flight", "book_hotel": "hotel"},
        ignore_tools={"search_web"},   # skip authorization for read-only tools
    )],
)

# Option B — tool guard: wraps a single tool in-place, hard enforcement
FiGuardToolGuard(
    tool=book_flight_tool,
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",
    amount_key="price",
)
```

`FiGuardCallbackHandler` raises `ToolException` on denial — the LLM receives the denial reason and can try an alternative. `FiGuardToolGuard` patches `tool._run` directly so the tool never runs regardless of `AgentExecutor` configuration.

### CrewAI

```bash
pip install figuard[crewai]
```

```python
from figuard.integrations.crewai import FiGuardCrewGuard

FiGuardCrewGuard(
    tool=book_flight_tool,
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",
    amount_key="price",
)
travel_agent = Agent(role="Travel Coordinator", tools=[book_flight_tool])
```

### OpenAI Agents SDK

```bash
pip install figuard[openai-agents]
```

```python
from agents import function_tool
from figuard.integrations.openai_agents import guarded_function_tool

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",
    amount_key="price",
)
def book_flight(destination: str, price: float) -> str:
    """Book a flight to the specified destination."""
    ...
```

Apply `@guarded_function_tool` as the **inner** decorator (before `@function_tool`) so FiGuard wraps the raw function and has access to all kwargs.

### OpenAI Function Calling

```bash
pip install figuard[openai]
```

```python
import json
from figuard.integrations.openai import guarded_openai_function

@guarded_openai_function(
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",
)
def book_flight(destination: str, amount: float) -> str:
    ...

# Dispatch in your tool call loop:
for tool_call in response.choices[0].message.tool_calls:
    if tool_call.function.name == "book_flight":
        result = book_flight(**json.loads(tool_call.function.arguments))
```

### Anthropic Tool Use

```bash
pip install figuard[anthropic]
```

```python
from figuard.integrations.anthropic import guarded_anthropic_tool

@guarded_anthropic_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",
)
def book_flight(destination: str, amount: float) -> str:
    ...

# Dispatch in your tool use loop (Anthropic passes block.input as a dict):
for block in response.content:
    if block.type == "tool_use" and block.name == "book_flight":
        result = book_flight(**block.input)
```

### Denial handling across all integrations

When a tool call is denied, each integration handles it differently:

| Integration | Denial behavior |
|---|---|
| `FiGuardCallbackHandler` | Raises `ToolException` — LLM receives denial reason |
| `FiGuardToolGuard` | Returns denial string — LLM receives denial reason |
| `FiGuardCrewGuard` | Returns denial string — LLM receives denial reason |
| `guarded_function_tool` | Returns denial string — LLM receives denial reason |
| `guarded_openai_function` | Returns denial string — return as tool result to the model |
| `guarded_anthropic_tool` | Returns denial string — return in `tool_result` block to Claude |

The denial string format: `"FiGuard DENIED: <code> — <message>"`, e.g.:
```
FiGuard DENIED: INSUFFICIENT_FUNDS — flight allocation has $0.00 remaining
```

---

## License

Apache 2.0 — see [LICENSE](https://github.com/figuard/figuard-core/blob/main/LICENSE) for details.
