# Framework Integrations

FiGuard integrates with LangChain, CrewAI, OpenAI Agents SDK, and Anthropic. Each integration intercepts tool calls automatically — you don't write explicit `authorize()` calls.

For direct SDK usage (no framework), see the [60-second quickstart](../README.md#60-second-quickstart).

---

## LangChain

```bash
pip install "figuard[langchain]" langchain-openai
```

```python
from langchain_openai import ChatOpenAI
from figuard.integrations.langchain import FiGuardCallbackHandler
from figuard import FiGuardClient

client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")

budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)

handler = FiGuardCallbackHandler(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="langchain_agent",
)

llm = ChatOpenAI(callbacks=[handler])
# Every tool call is now pre-flight authorized.
# Denied tool calls return a structured denial string as the tool result.
```

The handler intercepts `on_tool_start` events. It extracts the spend amount from the tool input (configured via `amount_key`) and calls `authorize()` before the tool executes. If denied, the tool is not called and the denial reason is returned to the LLM as the tool result.

---

## CrewAI

```bash
pip install "figuard[crewai]" crewai
```

```python
from crewai import Crew, Agent, Task
from figuard.integrations.crewai import FiGuardCrewGuard
from figuard import FiGuardClient

client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")

budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)

guard = FiGuardCrewGuard(
    client=client,
    session_token=budget.primary_token.session_token,
)

crew = Crew(agents=[...], tasks=[...])
guard.wrap(crew)   # patches tool execution on all agents in the crew
result = crew.kickoff()
```

`FiGuardCrewGuard.wrap()` patches the tool execution pipeline on every agent in the crew. The guard is applied once at the crew level — you don't need to modify individual agents or tasks.

---

## OpenAI Agents SDK

```bash
pip install "figuard[openai-agents]" openai-agents
```

The OpenAI Agents integration uses a decorator pattern. Apply `@guarded_function_tool` to your tool function before `@function_tool`.

```python
from agents import Agent, Runner, function_tool
from figuard.integrations.openai_agents import guarded_function_tool
from figuard import FiGuardClient

client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")

budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)

@function_tool
@guarded_function_tool(
    client=client,
    session_token=budget.primary_token.session_token,
    category="flight",     # maps to claimed_category
    amount_key="price",    # which kwarg holds the spend amount
    agent_id="travel_agent",
)
def book_flight(destination: str, price: float) -> str:
    """Book a flight to the destination."""
    # real implementation
    return f"Flight to {destination} booked for ${price}"

agent = Agent(name="travel_agent", tools=[book_flight])
result = Runner.run_sync(agent, "Book a flight to NYC for $299")
```

**Decorator order matters.** Apply `@guarded_function_tool` as the inner decorator (closer to the function) and `@function_tool` as the outer decorator. This lets FiGuard wrap the raw Python function before the Agents SDK converts it to a JSON schema.

When a tool call is denied, the function returns a structured denial string to the agent:
```
"FiGuard DENIED: INSUFFICIENT_FUNDS — no remaining budget for flights"
```

The agent receives this as the tool result and can adjust its plan.

---

## Anthropic (tool use)

```bash
pip install "figuard[anthropic]" anthropic
```

```python
from anthropic import Anthropic
from figuard.integrations.anthropic import FiGuardAnthropicGuard
from figuard import FiGuardClient

client = FiGuardClient(api_key="sb_live_demo", base_url="https://sandbox.figuard.io")

budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)

guard = FiGuardAnthropicGuard(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="claude_agent",
)

anthropic_client = Anthropic()
# Use guard.run_tool() in your tool execution loop to pre-authorize each call
```

See `sdk/python/figuard/integrations/anthropic.py` for the full tool execution loop pattern.

---

## What all integrations share

- Budget creation is identical across all frameworks — use whatever `create_budget` config you need.
- Denied tool calls are surfaced to the LLM as tool results, not exceptions. The agent decides how to proceed.
- All authorizations and denials appear in the ledger at `GET /api/v1/budgets/{id}/ledger`.
- Framework integrations work with single-agent budgets. For fleet delegation, use the raw SDK.
