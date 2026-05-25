# LangChain + FiGuard

By the end of this guide your LangChain agent will ask FiGuard for permission before every tool call that spends money. Approved calls go through. Denied calls return a structured message to the LLM — the agent adjusts its plan instead of crashing.

---

## Install

```bash
pip install "figuard[langchain]" langchain-openai
```

---

## Step 1: Run the demo in 5 minutes

Paste this and run it. No sign-up needed — the sandbox key is already in the code.

```python
from langchain_openai import ChatOpenAI
from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain.tools import tool
from langchain_core.prompts import ChatPromptTemplate
from figuard import FiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler

# --- FiGuard setup ---
client = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)
budget = client.create_budget(
    user_id="demo_user",
    total_limit=100.00,   # $100 total
    currency="USD",
    expires_in="1h",
)
handler = FiGuardCallbackHandler(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="demo_agent",
)

# --- Your tools (unchanged — no FiGuard code here) ---
@tool
def book_hotel(city: str, amount: float) -> str:
    """Book a hotel room. Args: city (str), amount (float USD)"""
    return f"Hotel in {city} booked for ${amount}"

@tool
def send_wire(recipient: str, amount: float) -> str:
    """Send a wire transfer. Args: recipient (str), amount (float USD)"""
    return f"Wire of ${amount} sent to {recipient}"

# --- Agent setup (pass handler as callback) ---
llm = ChatOpenAI(model="gpt-4o-mini", callbacks=[handler])
prompt = ChatPromptTemplate.from_messages([
    ("system", "You are a helpful travel assistant."),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])
agent = create_tool_calling_agent(llm, [book_hotel, send_wire], prompt)
executor = AgentExecutor(agent=agent, tools=[book_hotel, send_wire], callbacks=[handler])

# --- Run it ---
result = executor.invoke({"input": "Book a hotel in Paris for $80, then send a $200 wire to Alice."})
print(result["output"])
```

**What you'll see:**

```
> Entering new AgentExecutor chain...
  tool=book_hotel amount=80.0 → AUTHORIZED ✓
  Hotel in Paris booked for $80
  tool=send_wire amount=200.0 → DENIED: BUDGET_EXHAUSTED (remaining: $20.00)
  
I booked a hotel in Paris for $80. However, I was unable to send the $200 wire
to Alice — the remaining budget ($20) is insufficient. Would you like to send
a smaller amount or add funds to the budget?
```

The agent didn't crash. FiGuard returned a structured denial string as the tool result. The LLM explained the situation and offered alternatives.

---

## Step 2: Control what gets authorized

By default, FiGuard looks for a key called `amount` in tool arguments. If your tools use different names, set `amount_key`:

```python
handler = FiGuardCallbackHandler(
    client=client,
    session_token=budget.primary_token.session_token,
    agent_id="demo_agent",
    amount_key="price",      # reads "price" instead of "amount"
)
```

**Handler vs. tool guard — when to use which:**

- `FiGuardCallbackHandler` — attach once to the executor; all tools share the same budget, session token, and category. Right for most single-agent setups.
- `FiGuardToolGuard` — wrap individual tools when you need different categories or session tokens per tool. The handler is not needed if you use tool guards; they enforce independently.

```python
from langchain.tools import StructuredTool
from figuard.integrations.langchain import FiGuardToolGuard

# Wrap individual tools with explicit categories — no handler needed
flight_tool = StructuredTool.from_function(book_flight)
FiGuardToolGuard(
    tool=flight_tool,
    client=client,
    session_token=budget.primary_token.session_token,
    category="flights",
    amount_key="price",
)
```

`FiGuardToolGuard` patches `_run` directly on the tool object — the tool never executes if denied, regardless of `handle_tool_error` settings on the executor.

---

## Step 3: Per-category spending limits

Create a budget with allocations to cap spending by category:

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
```

Now a `book_hotel` tool call for $400 gets `ALLOCATION_EXHAUSTED` even if the total budget has funds remaining. The LLM sees:

```
FiGuard DENIED: ALLOCATION_EXHAUSTED — hotels allocation limit is $300.00,
$300.00 already spent
```

---

## Step 4: Multi-agent (fleet) setup

When you have multiple agents sharing a parent budget, use delegation tokens so each agent has its own spending cap:

```python
token = client.create_delegation_token(
    budget_id=budget.id,
    sub_agent_id="researcher",
    cap=200.00,
)

researcher_handler = FiGuardCallbackHandler(
    client=client,
    session_token=token.session_token,  # scoped token, not the parent
    agent_id="researcher",
)
```

Each agent's spending counts against both its own cap and the parent budget. If the researcher hits $200, it's denied — the orchestrator's remaining funds are untouched.

---

## Step 5: See the full audit trail

After any run:

```python
ledger = client.get_budget(budget.id)
print(f"Spent: ${ledger.quantity_spent} / ${ledger.total_limit}")

events = client.list_events(budget.id)
for e in events:
    print(f"  {e.decision:10} {e.action_type:20} ${e.requested_quantity}")
```

```
Spent: $80.00 / $100.00

  CONFIRMED   book_hotel           $80.0
  DENIED      send_wire            $200.0
```

---

## Step 6: Cancel an orchestration job atomically

When a LangGraph job fails or is cancelled mid-run, child agents may have live reservations that are holding budget. Call `void_tree` on the root event to release all of them in a single call.

```python
import asyncio
from langgraph.graph import StateGraph
from figuard import FiGuardClient

client = FiGuardClient(api_key="fg_live_...", base_url="https://api.figuard.io")
budget = client.create_budget(user_id="user_123", total_limit=500.00, currency="USD", expires_in="1h")

# Orchestrator authorizes the job
root = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="orchestrator",
    action_type="ORCHESTRATION_JOB",
    description="Refund batch run #1042",
    requested_quantity=500.00,
    idempotency_key="job-1042",
)

# ... sub-agents spawn and authorize their own events with parent_event_id=root.event_id ...

# Job fails or is cancelled — release everything atomically
if root.is_authorized:
    result = client.void_tree(
        event_id=root.event_id,
        reason="ORCHESTRATION_JOB_CANCELLED",
    )
    print(f"Released ${result.total_quantity_released} across {result.voided_count} events")
    print(f"Voided IDs: {result.voided_event_ids}")
```

```
Released $420.00 across 4 events
Voided IDs: ['evt_abc123', 'evt_def456', 'evt_ghi789', 'evt_jkl012']
```

Without `void_tree`, each child's reservation would stay frozen until `authorizationExpirySeconds` elapses. With `void_tree`, the full $420 is available for the next job immediately.

You can subscribe to `SPEND_TREE_VOIDED` webhooks to monitor orchestration cancellations:

```python
client.create_webhook(
    url="https://your-server.com/figuard-events",
    secret="your-webhook-secret",
    events=["SPEND_TREE_VOIDED", "SPEND_DENIED"],
)
# Payload includes: rootEventId, voidedCount, totalQuantityReleased, voidedEventIds
```

---

## Step 7: Automatic causal chain construction

The handler automatically links sub-agent events into a causal chain — no manual
`parent_event_id` plumbing needed.

Every LangChain/LangGraph execution unit has a `run_id` UUID and a `parent_run_id`.
The handler records these for every node (including ones that never authorize) in an
in-memory topology table. When a tool fires, it walks up the topology to find the nearest
instrumented ancestor and uses its FiGuard event_id as `parent_event_id`:

```
Orchestrator (run A) → authorizes → event_id = evt_A
  └── Planner node (run B) → no authorize
       └── Tool C (run C, parent=B) → authorize, walk-up: B→A → parent_event_id=evt_A ✓
```

**Parallel graph race** — in LangGraph graphs with parallel branches, two nodes can start
simultaneously. If Node B fires before Node A's authorization completes, B's authorize call
is buffered and dispatched the moment A's event_id arrives. No manual ordering needed.

**Thread pool context** — LangGraph's parallel execution uses a `ThreadPoolExecutor`.
Python `ContextVar` does not propagate into these threads. Use `figuard_run_in_executor`
for any async graph with parallel branches:

```python
from figuard import figuard_run_in_executor

# ❌ Breaks the causal chain in parallel branches:
await loop.run_in_executor(None, process_refund, order_id)

# ✅ Carries parent_event_id into the worker thread:
await figuard_run_in_executor(process_refund, order_id)
```

**Partial instrumentation** — you don't need to instrument every node. Routing, planning,
and summarization nodes that don't spend money can be left un-authorized. The walk-up
skips them and links spending tools to the nearest instrumented ancestor transparently.

---

## Reference

| Concept | Code |
|---------|------|
| Basic setup | `FiGuardCallbackHandler(client, session_token, agent_id)` |
| Custom amount key | `amount_key="price"` |
| Per-tool category | `FiGuardToolGuard(tool, ..., category="flights")` |
| Fleet sub-agent | `create_delegation_token(budget_id, sub_agent_id, cap)` |
| Audit trail | `client.list_events(budget_id)` |
| Cancel orchestration job | `client.void_tree(event_id, reason)` — releases entire causal subtree atomically |
| Parallel thread pool | `figuard_run_in_executor(fn, *args)` — carries ContextVar context into worker threads |
| Pin ambient parent | `with figuard_scope(event_id): ...` — scopes all authorize() calls inside the block |
| Test policies without side effects | Pass `dry_run=True` to `client.authorize()` — checks run, nothing is written |

> **Sandbox limits:** `sb_live_demo` is shared and rate-limited. If you hit limits, [self-host in 2 minutes](../self-hosting.md) and use your own key.
