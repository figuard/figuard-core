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

## Reference

| Concept | Code |
|---------|------|
| Basic setup | `FiGuardCallbackHandler(client, session_token, agent_id)` |
| Custom amount key | `amount_key="price"` |
| Per-tool category | `FiGuardToolGuard(tool, ..., category="flights")` |
| Fleet sub-agent | `create_delegation_token(budget_id, sub_agent_id, cap)` |
| Audit trail | `client.list_events(budget_id)` |
| Test policies without side effects | Pass `dry_run=True` to `client.authorize()` — checks run, nothing is written |

> **Sandbox limits:** `sb_live_demo` is shared and rate-limited. If you hit limits, [self-host in 2 minutes](../self-hosting.md) and use your own key.
