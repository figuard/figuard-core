# LangGraph + FiGuard

LangGraph builds multi-agent pipelines as state graphs — nodes connected by edges, running synchronously or in parallel branches. FiGuard integrates via the same `FiGuardCallbackHandler` used for LangChain's `AgentExecutor`, but the wiring is different because LangGraph uses a graph config rather than an executor.

---

## Install

```bash
pip install "figuard[langchain]" langgraph langchain-openai
```

---

## Step 1: Wire the handler into the graph

Pass `FiGuardCallbackHandler` through the graph's `RunnableConfig`:

```python
import asyncio
from typing import TypedDict, Annotated
from langgraph.graph import StateGraph, END
from langgraph.prebuilt import ToolNode
from langchain_openai import ChatOpenAI
from langchain.tools import tool
from figuard import FiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler

# FiGuard setup
client = FiGuardClient()  # zero-config sandbox
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)
handler = FiGuardCallbackHandler(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="langgraph_agent",
)

# Tools
@tool
def book_flight(destination: str, amount: float) -> str:
    """Book a flight. Args: destination (str), amount (float USD)."""
    return f"Flight to {destination} booked for ${amount}"

@tool
def book_hotel(city: str, amount: float) -> str:
    """Book a hotel. Args: city (str), amount (float USD)."""
    return f"Hotel in {city} booked for ${amount}"

tools = [book_flight, book_hotel]

# Graph state
class AgentState(TypedDict):
    messages: Annotated[list, lambda x, y: x + y]

# Model
llm = ChatOpenAI(model="gpt-4o-mini").bind_tools(tools)

def agent_node(state: AgentState) -> AgentState:
    return {"messages": [llm.invoke(state["messages"])]}

def should_continue(state: AgentState) -> str:
    last = state["messages"][-1]
    return "tools" if last.tool_calls else END

# Build graph
graph = StateGraph(AgentState)
graph.add_node("agent", agent_node)
graph.add_node("tools", ToolNode(tools))
graph.set_entry_point("agent")
graph.add_conditional_edges("agent", should_continue)
graph.add_edge("tools", "agent")
app = graph.compile()

# Run — pass handler via config
result = app.invoke(
    {"messages": [("human", "Book a flight to Rome for $280 and a hotel for $150.")]},
    config={"callbacks": [handler]},
)
print(result["messages"][-1].content)
```

**What you'll see:**

```
book_flight(destination="Rome", amount=280.0) → AUTHORIZED ✓
book_hotel(city="Rome", amount=150.0) → AUTHORIZED ✓

I've booked a flight to Rome for $280 and a hotel for $150.
Total spent: $430 of your $500 budget.
```

---

## Step 2: Async graphs (the default for production)

LangGraph's production usage is almost always async. Use `AsyncFiGuardClient` and `ainvoke`:

```python
import asyncio
from figuard import AsyncFiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler

async def main():
    async with AsyncFiGuardClient() as client:
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            currency="USD",
            expires_in="24h",
        )
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=budget.primary_token.session_token,
            agent_id="async_agent",
        )

        result = await app.ainvoke(
            {"messages": [("human", "Book a flight to Rome for $280.")]},
            config={"callbacks": [handler]},
        )
        print(result["messages"][-1].content)

asyncio.run(main())
```

---

## Step 3: Parallel branches and the thread pool trap

LangGraph runs parallel branches in a `ThreadPoolExecutor`. Python's `ContextVar` does **not** propagate into thread pool workers automatically — this breaks FiGuard's causal chain linking (child events lose track of their parent).

Use `figuard_run_in_executor` instead of `loop.run_in_executor()` anywhere you spawn threads inside a graph node:

```python
from figuard import figuard_run_in_executor

# ❌ Breaks causal chain — ContextVar not propagated
result = await loop.run_in_executor(None, process_booking, order_id)

# ✅ Correct — carries FiGuard context into the worker thread
result = await figuard_run_in_executor(process_booking, order_id)
```

This only matters if your graph nodes manually dispatch to a thread pool. LangGraph's built-in `ToolNode` handles this correctly for you.

---

## Step 4: Cancelling a graph run atomically

When a graph run fails or is cancelled mid-flight, child agents may have live reservations holding budget. Use `void_tree` on the root authorization to release everything in one call:

```python
from figuard import FiGuardClient, figuard_scope

client = FiGuardClient()
budget = client.create_budget(user_id="user_123", total_limit=500.00, currency="USD", expires_in="1h")

# Authorize the top-level job
root = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="orchestrator",
    action_type="ORCHESTRATION_JOB",
    description="Travel planning run #42",
    requested_quantity=500.00,
    idempotency_key="run-42",
)

# Pin root event_id as ambient parent — all authorize() calls inside this block
# automatically set parent_event_id=root.event_id, building the causal tree
with figuard_scope(root.event_id):
    result = app.invoke(
        {"messages": [("human", "Plan a 3-day trip to Paris under $500.")]},
        config={"callbacks": [handler]},
    )

# If the run fails, void everything at once
# result = client.void_tree(event_id=root.event_id, reason="ORCHESTRATION_JOB_CANCELLED")
# print(f"Released ${result.total_quantity_released} across {result.voided_count} events")
```

Without `void_tree`, each child reservation stays frozen until `authorization_expiry_seconds` elapses. With it, all capacity is returned immediately.

---

## Step 5: Per-agent spending caps in a multi-agent graph

When your graph has multiple specialist agents sharing a parent budget, give each a delegation token:

```python
# Create scoped tokens per agent
researcher_token = client.create_delegation_token(
    budget_id=budget.id,
    label="researcher",
    caps=[{"category": "data_apis", "limit": 100.00}],
)
writer_token = client.create_delegation_token(
    budget_id=budget.id,
    label="writer",
    caps=[{"category": "llm_calls", "limit": 200.00}],
)

# Each agent gets its own handler with its own scoped token
researcher_handler = FiGuardCallbackHandler(
    client=client,
    session_token=researcher_token.session_token,
    agent_id="researcher",
)
writer_handler = FiGuardCallbackHandler(
    client=client,
    session_token=writer_token.session_token,
    agent_id="writer",
)

# Pass the right handler to each node via config
researcher_result = researcher_node.invoke(state, config={"callbacks": [researcher_handler]})
writer_result = writer_node.invoke(state, config={"callbacks": [writer_handler]})
```

Each agent's spending reduces both its own cap and the parent budget. If the researcher exhausts its $100, it's denied — the writer's $200 is unaffected.

---

## Step 6: Handling denials in graph nodes

When a tool call is denied, the denial string becomes the tool node output. Your agent node should handle it:

```python
def agent_node(state: AgentState) -> AgentState:
    last = state["messages"][-1]
    # Check for denial in tool results
    for msg in state["messages"]:
        if hasattr(msg, "content") and "FiGuard DENIED" in str(msg.content):
            # Route to a fallback node or surface to user
            return {"messages": [AIMessage(content=f"Budget limit reached: {msg.content}")]}
    return {"messages": [llm.invoke(state["messages"])]}
```

See [Denial Handling Guide](../denial-handling.md) for all denial codes and prompt instructions.

---

## Reference

| Concept | Code |
|---|---|
| Attach handler to graph | `config={"callbacks": [handler]}` in `.invoke()` / `.ainvoke()` |
| Async graph | `AsyncFiGuardClient()` + `app.ainvoke(...)` |
| Parallel branch context | `figuard_run_in_executor(fn, *args)` |
| Pin ambient parent | `with figuard_scope(root_event_id): ...` |
| Cancel run atomically | `client.void_tree(event_id, reason)` |
| Per-agent cap | `create_delegation_token(budget_id, label, caps)` |
| Denial handling | See [docs/denial-handling.md](../denial-handling.md) |
