# OpenAI Agents SDK + FiGuard

By the end of this guide your OpenAI agent tools will be spend-authorized by FiGuard. The agent cannot call a guarded tool without FiGuard approving it first.

---

## Install

```bash
pip install "figuard[openai-agents]" openai-agents
```

---

## Step 1: Run the demo in 5 minutes

```python
from agents import Agent, Runner, function_tool
from figuard import FiGuardClient
from figuard.integrations.openai_agents import guarded_function_tool

# --- FiGuard setup ---
client = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-g1ha.onrender.com",
)
budget = client.create_budget(
    user_id="demo_user",
    total_limit=100.00,
    currency="USD",
    expires_in="1h",
)

# --- Define tools ---
# Decorator order matters: @guarded_function_tool is the INNER decorator.
# FiGuard wraps the raw function first, then @function_tool converts it to a schema.

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    amount_key="price",
)
def book_flight(destination: str, price: float) -> str:
    """Book a flight. Args: destination (str), price (float USD)"""
    return f"Flight to {destination} booked for ${price}"

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    amount_key="price",
)
def book_hotel(city: str, nights: int, price: float) -> str:
    """Book a hotel. Args: city (str), nights (int), price (float USD per night)"""
    return f"{nights}-night hotel in {city} booked for ${price}/night"

# --- Run ---
agent = Agent(
    name="travel_agent",
    instructions="You are a travel booking assistant.",
    tools=[book_flight, book_hotel],
)

result = Runner.run_sync(
    agent,
    "Book a flight to Tokyo for $90, then book a hotel in Tokyo for 3 nights at $50/night."
)
print(result.final_output)
```

**What you'll see:**

```
Flight to Tokyo booked for $90.

I tried to book the hotel ($50/night × 3 = $150 total) but FiGuard returned:
"FiGuard DENIED: BUDGET_EXHAUSTED — $10.00 remaining, $150.00 requested"

I can book 1 night for $50 if you'd like, or you could increase the budget.
```

The second tool call was denied before the function body ran. The denial string became the tool result — the agent reasoned about it and offered alternatives.

---

## Step 2: Decorator order — the one thing to get right

```python
# ✅ Correct — FiGuard wraps the raw function, then Agents SDK converts it
@function_tool
@guarded_function_tool(client=client, session_token=..., agent_id=...)
def my_tool(amount: float) -> str: ...

# ❌ Wrong — Agents SDK converts first, FiGuard can't intercept cleanly
@guarded_function_tool(client=client, session_token=..., agent_id=...)
@function_tool
def my_tool(amount: float) -> str: ...
```

---

## Step 3: Per-category limits

Give each tool an explicit spend category and create a budget with allocations:

```python
budget = client.create_budget(
    user_id="demo_user",
    total_limit=1000.00,
    currency="USD",
    expires_in="24h",
    allocations=[
        {"category": "flights", "limit": 700.00},
        {"category": "hotels",  "limit": 400.00},
    ],
)

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    category="flights",     # ← maps to claimed_category
    amount_key="price",
)
def book_flight(destination: str, price: float) -> str: ...

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    category="hotels",
    amount_key="price",
)
def book_hotel(city: str, price: float) -> str: ...
```

Now a flight that would push the flights allocation past $700 is denied with `ALLOCATION_EXHAUSTED`, even if the total budget has funds.

---

## Step 4: Multi-agent pipelines

When one agent orchestrates others, give each sub-agent a scoped delegation token:

```python
researcher_token = client.create_delegation_token(
    budget_id=budget.id,
    sub_agent_id="researcher",
    cap=200.00,
    allocations=[{"category": "data_apis", "limit": 200.00}],
)

@function_tool
@guarded_function_tool(
    client=client,
    session_token=researcher_token.session_token,  # scoped, not parent token
    agent_id="researcher",
    category="data_apis",
    amount_key="cost",
)
def fetch_market_data(query: str, cost: float) -> str: ...
```

The researcher can spend up to $200. Its spending reduces the parent budget too. If the orchestrator revokes the token (`client.revoke_delegation_token(token_id)`), all further calls are denied immediately.

---

## Step 5: Async agents

Both client and guard are async-compatible:

```python
from figuard import AsyncFiGuardClient
from figuard.integrations.openai_agents import guarded_function_tool

client = AsyncFiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-g1ha.onrender.com",
)

# Same decorator pattern — guarded_function_tool detects async context automatically
@function_tool
@guarded_function_tool(client=client, session_token=..., agent_id=...)
async def fetch_data(query: str, cost: float) -> str: ...
```

---

## Reference

| Concept | Code |
|---------|------|
| Basic guard | `@function_tool` then `@guarded_function_tool(client, session_token, agent_id)` |
| Custom amount key | `amount_key="price"` (default: `"amount"`) |
| Spend category | `category="flights"` |
| Scoped sub-agent | `create_delegation_token(budget_id, sub_agent_id, cap)` |
| Async | `AsyncFiGuardClient` — same decorator |
| Test policies without side effects | Pass `dry_run=True` to `client.authorize()` — checks run, nothing written |

> **Sandbox limits:** `sb_live_demo` is shared and rate-limited. [Self-host](../self-hosting.md) for real workloads.
