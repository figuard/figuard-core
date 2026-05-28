# CrewAI + FiGuard

By the end of this guide every tool call made by any agent in your crew will be spend-authorized by FiGuard before it executes.

---

## Install

```bash
pip install "figuard[crewai]" crewai crewai-tools
```

---

## Step 1: Run the demo in 5 minutes

```python
from crewai import Agent, Task, Crew
from crewai.tools import BaseTool
from pydantic import BaseModel
from figuard import FiGuardClient
from figuard.integrations.crewai import FiGuardCrewGuard

# --- FiGuard setup ---
client = FiGuardClient()  # zero-config: connects to shared sandbox automatically
budget = client.create_budget(
    user_id="demo_user",
    total_limit=200.00,
    currency="USD",
    expires_in="1h",
)
guard = FiGuardCrewGuard(
    client=client,
    session_token=budget.primary_token.session_token,
)

# --- Define tools (no FiGuard code here) ---
class BookFlightInput(BaseModel):
    destination: str
    amount: float

class BookFlightTool(BaseTool):
    name: str = "book_flight"
    description: str = "Book a flight. Provide destination and amount (USD)."
    args_schema: type[BaseModel] = BookFlightInput

    def _run(self, destination: str, amount: float) -> str:
        return f"Flight to {destination} booked for ${amount}"

class BookHotelInput(BaseModel):
    city: str
    amount: float

class BookHotelTool(BaseTool):
    name: str = "book_hotel"
    description: str = "Book a hotel. Provide city and amount (USD)."
    args_schema: type[BaseModel] = BookHotelInput

    def _run(self, city: str, amount: float) -> str:
        return f"Hotel in {city} booked for ${amount}"

# --- Crew setup ---
tools = [BookFlightTool(), BookHotelTool()]

# ⚠️ wrap_tools mutates the tool objects in place — it does not copy them.
# Don't pass the same tool list to two different guards or you'll get double-wrapping.
# Create fresh tool instances if you need to share tools across multiple guards.
guard.wrap_tools(tools)

planner = Agent(
    role="Travel Planner",
    goal="Book travel within budget",
    backstory="Experienced travel agent who respects spending limits.",
    tools=tools,
)

task = Task(
    description="Book a flight to Rome for $180 and a hotel for $80.",
    expected_output="Booking confirmation or explanation if denied.",
    agent=planner,
)

crew = Crew(agents=[planner], tasks=[task], verbose=True)
result = crew.kickoff()
print(result)
```

**What you'll see:**

```
[Travel Planner] → book_flight(destination="Rome", amount=180.0)
  FiGuard: AUTHORIZED ✓ ($180.00 reserved)

[Travel Planner] → book_hotel(city="Rome", amount=80.0)
  FiGuard: DENIED — BUDGET_EXHAUSTED ($20.00 remaining, $80.00 requested)

I booked a flight to Rome for $180. The hotel booking was denied —
only $20 remains in the budget. Consider a cheaper option or increase the limit.
```

---

## Step 2: Wrap a whole crew at once

If you have multiple agents, you can wrap the whole crew instead of individual tools:

```python
crew = Crew(agents=[planner, researcher, executor], tasks=[...])
guard.wrap(crew)   # wraps all tools on all agents in one call
result = crew.kickoff()
```

`wrap()` patches all tools on all agents in one call. `wrap_tools([...])` patches a specific list. Both mutate in place — see the warning in Step 1.

---

## Step 3: Per-agent spending limits

Give each agent a delegation token so they have their own cap:

```python
planner_token = client.create_delegation_token(
    budget_id=budget.id,
    sub_agent_id="planner",
    cap=300.00,
)
researcher_token = client.create_delegation_token(
    budget_id=budget.id,
    sub_agent_id="researcher",
    cap=100.00,
)

planner_guard  = FiGuardCrewGuard(client=client, session_token=planner_token.session_token)
researcher_guard = FiGuardCrewGuard(client=client, session_token=researcher_token.session_token)

planner_guard.wrap_tools(planner_tools)
researcher_guard.wrap_tools(researcher_tools)
```

Each agent can spend up to its cap. All spending reduces the parent budget. If the researcher exhausts its $100, it's blocked — the planner's $300 is unaffected.

---

## Step 4: Per-category limits

```python
budget = client.create_budget(
    user_id="demo_user",
    total_limit=1000.00,
    currency="USD",
    expires_in="24h",
    allocations=[
        {"category": "flights", "limit": 600.00},
        {"category": "hotels",  "limit": 300.00},
    ],
)

guard = FiGuardCrewGuard(
    client=client,
    session_token=budget.primary_token.session_token,
    tool_categories={
        "book_flight": "flights",
        "book_hotel":  "hotels",
    },
)
guard.wrap_tools(tools)
```

A hotel booking that pushes the hotels allocation past $300 is denied with `ALLOCATION_EXHAUSTED` even if the total budget has money left.

---

## Step 5: Custom amount extraction

If your tool arguments don't use `amount`, provide a custom extractor:

```python
guard = FiGuardCrewGuard(
    client=client,
    session_token=budget.primary_token.session_token,
    amount_extractor=lambda tool_name, kwargs: kwargs.get("total_cost") or kwargs.get("price", 0.0),
)
```

The extractor receives the tool name and the full kwargs dict. Return the spend amount as a float.

---

## Step 6: Async CrewAI

CrewAI supports async execution via `kickoff_async`. Use `AsyncFiGuardClient` and `await` the guard setup:

```python
import asyncio
from figuard import AsyncFiGuardClient
from figuard.integrations.crewai import FiGuardCrewGuard

async def main():
    async with AsyncFiGuardClient() as client:
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            currency="USD",
            expires_in="24h",
        )
        guard = FiGuardCrewGuard(
            client=client,
            session_token=budget.primary_token.session_token,
        )
        guard.wrap(crew)
        result = await crew.kickoff_async(inputs={"topic": "book travel"})

asyncio.run(main())
```

Install the async extra: `pip install "figuard[async,crewai]"`

---

## Reference

| Concept | Code |
|---------|------|
| Wrap specific tools | `guard.wrap_tools([tool1, tool2])` — mutates in place |
| Wrap entire crew | `guard.wrap(crew)` — mutates in place |
| Per-agent cap | `create_delegation_token(budget_id, sub_agent_id, cap)` |
| Per-category limits | `tool_categories={"book_flight": "flights"}` |
| Custom amount | `amount_extractor=lambda name, kwargs: kwargs["price"]` |
| Test policies without side effects | Pass `dry_run=True` to `client.authorize()` — checks run, nothing written |

> **Sandbox limits:** `sb_live_demo` is shared and rate-limited. [Self-host](../self-hosting.md) for real workloads.
