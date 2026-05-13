# FiGuard

**Pre-flight spend authorization for AI agents.**  
Your agent asks permission before money moves. FiGuard says yes or no — and keeps a complete audit trail either way.  
Stop runaway agent purchases before they happen.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![PyPI](https://img.shields.io/pypi/v/figuard)](https://pypi.org/project/figuard/)
[![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)](https://www.python.org/)
[![npm](https://img.shields.io/npm/v/figuard?label=npm%20(ts-sdk)&color=cb3837)](https://www.npmjs.com/package/figuard)
[![npm](https://img.shields.io/npm/v/figuard-mcp?label=figuard-mcp&color=cb3837)](https://www.npmjs.com/package/figuard-mcp)

---

## The Problem

```python
# Without FiGuard — the agent decides to spend
purchases = [
    {"amount": 267.00, "description": "JetBlue SFO→JFK", "category": "flight",    "key": "flight-001"},
    {"amount":  85.00, "description": "Travel insurance", "category": "insurance", "key": "ins-001"},
    {"amount": 198.00, "description": "Hotel — The Aster", "category": "hotel",    "key": "hotel-001"},
]
for item in purchases:
    stripe.charge(item["amount"])  # all three go through — no gates, no record
```

```python
# With FiGuard — authorize before anything moves
for item in purchases:
    auth = client.authorize(
        session_token=session_token,
        agent_id="travel_agent",
        action_type="PURCHASE",
        description=item["description"],
        requested_amount=item["amount"],
        claimed_category=item["category"],
        idempotency_key=item["key"],
    )
    if auth.is_authorized:
        stripe.charge(auth.approved_amount)
        client.confirm_event(auth.event_id, confirmed_amount=auth.approved_amount)
    else:
        log.warning("Blocked: %s — %s", item["description"], auth.denial_reason)

# ✓ flight:    AUTHORIZED — $267.00 charged
# ✗ insurance: DENIED     — NO_MATCHING_ALLOCATION
# ✓ hotel:     AUTHORIZED — $198.00 charged
```

The difference: **authorization happens before the transaction, not after.**  
Denied decisions are recorded in the ledger regardless. The agent always gets a structured, machine-readable response.

---

## Auth Explained

FiGuard has two types of credentials — they serve different purposes and must never be swapped.

**API keys** (`ab_live_...`) are service-to-service credentials. Your backend uses an API key to call FiGuard. One key per service. Treat like a database password — never pass it to agents.

**Session tokens** (`st_...`) are budget-scoped credentials. When you create a budget, FiGuard returns a session token. You hand this to the agent. The agent presents it on every `authorize()` call. The token is scoped to exactly one budget — if it leaks, an attacker can only spend up to that budget's remaining limit.

```
Your backend                          Agent
    │                                   │
    │  create_budget(api_key=...)        │
    │ ─────────────────────────▶         │
    │ ◀─────────────────────────         │
    │  budget.session_token              │
    │                                   │
    │  ──── pass session_token ────────▶ │
    │                                   │
    │                   authorize(session_token=...) │
    │ ◀─────────────────────────────────│
    │  AUTHORIZED / DENIED              │
```

---

## 60-Second Quickstart

**1. Start FiGuard**

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
make run
```

```
[FiGuard] Ready at http://localhost:8080
[FiGuard] Demo API key: ab_live_demo
```

The demo key (`ab_live_demo`) is created automatically on first start. It never changes unless you run `make reset`.

**2. Install the Python SDK**

```bash
pip install figuard
```

**3. Authorize your first spend**

```python
from figuard import FiGuardClient

client = FiGuardClient(
    api_key="ab_live_demo",
    base_url="http://localhost:8080",
)

# Create a budget for this agent session.
# expires_in accepts "24h", "7d", "30m", a timedelta, or seconds as int.
# authorization_expiry_seconds recycles stale reservations — set it to your
# agent's expected max run time so abandoned reservations don't lock funds forever.
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    expires_in="24h",
    authorization_expiry_seconds=300,
    intent_context="travel booking session",
)

# Pre-authorize before any spend.
# The agent estimates $270 — the actual charge may differ.
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK roundtrip",
    requested_amount=270.00,
    idempotency_key="booking-001",
)

print(auth.decision)        # AUTHORIZED
print(auth.approved_amount) # 270.0

# After the real transaction succeeds, confirm with the ACTUAL charged amount.
# This is the value that lands in your ledger as real spend — pass what was
# really charged, not just the approved amount.
actual_charge = 267.00
client.confirm_event(auth.event_id, confirmed_amount=actual_charge)
# If you never call confirm/fail/void, the reservation auto-releases
# after authorization_expiry_seconds (300s above).
```

---

## What FiGuard Is Not

**Not a payment processor.** FiGuard never touches money. It authorizes the *intent to spend* and records the decision. The actual payment goes through your existing processor (Stripe, Braintree, etc.) as before.

**Not a policy language.** Budget limits and allocation caps are structured data, not a DSL. FiGuard matches the category an agent declares against the categories you defined — nothing more.

**Not a firewall for human users.** FiGuard is purpose-built for agent-to-service authorization. The session token model assumes agents are ephemeral and untrusted by default.

**Not a replacement for Stripe spending controls.** Use both if you want defense in depth. FiGuard blocks at agent decision time; Stripe blocks at payment time. Different attack surfaces.

---

## How It Works

```
  Developer
  ──────────▶ create budget ──▶ session token issued to agent
              ($500 USD  or  100k tokens  or  any unit)
                        │
           ┌────────────┴────────────────────────────┐
           │                                         │
     single agent                            fleet agent
           │                          issue delegation tokens
           │                          ├─▶ sub-agent A ($3k refunds)
           │                          └─▶ sub-agent B ($5k compute)
           │                                         │
           └────────────┬────────────────────────────┘
                        │
              ┌─────────┴──────────┐
        monetary budget      resource budget
        currency: "USD"      unit: "tokens"
              └─────────┬──────────┘
                        │
                        ▼
  authorize()    ← nothing has moved yet
  checks: limit · category · expiry · anomaly · dedup
                        │
           ┌────────────┴────────────┐
        AUTHORIZED               DENIED
        funds reserved         nothing moves
           │                   structured denial code
           ▼
  [agent executes action]
  payment / API call / compute
           │
  ┌────────┼────────┐
succeeds  fails  cancelled
   │        │        │
confirm() fail()  void()
qty spent released released
   └────────┴────────┘
                        │
                        ▼
        ┌───────────────────────────────────┐
        │  every decision recorded in the   │
        │  append-only ledger — authorized, │
        │  denied, confirmed, failed, voided│
        └───────────────────────────────────┘
```

**Every path writes a `SpendEvent`.** Authorized, denied, confirmed, failed, voided — all land in the append-only ledger. You always have a complete audit trail.

**The lifecycle:**

```
authorize()  →  funds reserved, decision recorded    (AUTHORIZED or DENIED)
confirm()    →  reservation → confirmed spend
fail()       →  reservation released                 (payment processor declined)
void()       →  reservation released                 (action cancelled)
```

---

## Framework Integrations

FiGuard works with every major agent framework. Install only what you need.

### LangChain / LangGraph

```bash
pip install figuard[langchain]
```

**Callback handler** — attaches to `AgentExecutor`, guards every tool call automatically:

```python
from figuard.integrations.langchain import FiGuardCallbackHandler

executor = AgentExecutor(
    agent=agent,
    tools=tools,
    handle_tool_error=True,   # required — delivers denial reason to the LLM
    callbacks=[FiGuardCallbackHandler(
        client=client,
        session_token=budget.session_token,
        tool_category_map={"book_flight": "flight", "book_hotel": "hotel"},
        amount_extractor=lambda d: d.get("price") or d.get("amount", 0),
        # amount_extractor receives the parsed tool input dict.
        # Use it when your tools have different kwarg names for the spend amount.
        # Falls back to amount_param="amount" if amount_extractor is not set.
    )],
)
# Denied tool calls raise ToolException before the tool runs.
# The LLM receives the denial reason and can adjust its plan.
```

**Tool guard** — wraps a single tool in-place for hard enforcement:

```python
from figuard.integrations.langchain import FiGuardToolGuard

FiGuardToolGuard(
    tool=book_flight_tool,
    client=client,
    session_token=budget.session_token,
    category="flight",
    amount_extractor=lambda **kw: kw.get("price", 0),
    # debug=True prints what category + amount FiGuard is receiving — useful during setup
)
# book_flight_tool is now guarded — pass to create_react_agent as normal
```

### CrewAI

```bash
pip install figuard[crewai]
```

```python
from figuard.integrations.crewai import FiGuardCrewGuard

FiGuardCrewGuard(
    tool=book_flight_tool,
    client=client,
    session_token=budget.session_token,
    category="flight",
    amount_extractor=lambda **kw: kw.get("price", 0),
)
# Patches tool._run in-place. Pass the tool to Agent as normal.
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
    session_token=budget.session_token,
    category="flight",
    amount_extractor=lambda **kw: kw.get("price", 0),
)
def book_flight(destination: str, price: float) -> str:
    """Book a flight to the specified destination."""
    ...
# Apply @guarded_function_tool before @function_tool so FiGuard
# wraps the raw function — full access to kwargs before schema generation.
```

### OpenAI Function Calling

```bash
pip install figuard[openai]
```

```python
import json
from figuard.integrations.openai import guarded_openai_function

@guarded_openai_function(
    client=client,
    session_token=budget.session_token,
    category="flight",
    amount_extractor=lambda **kw: kw.get("price", 0),
)
def book_flight(destination: str, price: float) -> str:
    ...

# In your tool dispatch loop:
for tool_call in response.choices[0].message.tool_calls:
    if tool_call.function.name == "book_flight":
        args = json.loads(tool_call.function.arguments)
        result = book_flight(**args)   # FiGuard authorizes here
```

### Anthropic Tool Use

```bash
pip install figuard[anthropic]
```

```python
from figuard.integrations.anthropic import guarded_anthropic_tool

@guarded_anthropic_tool(
    client=client,
    session_token=budget.session_token,
    category="flight",
    amount_extractor=lambda **kw: kw.get("amount", 0),
)
def book_flight(destination: str, amount: float) -> str:
    ...

# In your tool dispatch loop (Anthropic passes block.input as a dict):
for block in response.content:
    if block.type == "tool_use" and block.name == "book_flight":
        result = book_flight(**block.input)   # FiGuard authorizes here
```

```bash
pip install figuard[all]   # install every framework extra at once
```

---

## TypeScript / Node.js SDK

```bash
npm install figuard
```

```typescript
import { FiGuardClient } from "figuard";

const client = new FiGuardClient({ apiKey: "ab_live_..." });

const budget = await client.createBudget({
  userId: "user_123",
  totalLimit: 500,
  currency: "USD",
  expiresIn: "24h",
});

const result = await client.authorize({
  sessionToken: budget.sessionToken,
  agentId: "travel_agent",
  actionType: "PURCHASE",
  description: "JetBlue SFO→JFK",
  requestedQuantity: 267,
  claimedCategory: "flight",
});

if (result.isAuthorized) {
  await client.confirmEvent({ eventId: result.eventId, confirmedQuantity: 267 });
}
```

Full type definitions included. No external runtime dependencies — uses native `fetch`.

---

## MCP Server (Claude Code · Cursor · Claude Desktop)

Use FiGuard directly from your AI coding assistant — no Python or TypeScript required.

```bash
npx figuard-mcp
```

Add to your MCP client config (Claude Code: `~/.claude.json`, Cursor: `~/.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "ab_live_...",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

13 tools exposed: `figuard_create_budget` · `figuard_authorize` · `figuard_confirm` · `figuard_fail` · `figuard_void` · `figuard_get_budget` · `figuard_get_ledger` · `figuard_resume_budget` · `figuard_extend_budget` · `figuard_cancel_batch` · `figuard_create_delegation_token` · `figuard_get_delegation_token` · `figuard_revoke_delegation_token`

The MCP server reads `FIGUARD_API_KEY` and `FIGUARD_BASE_URL` from its environment. No code changes needed to your agent.

---

## Enforcement Features

**Budget types**

```python
# Flat budget — total limit, any category accepted
budget = client.create_budget(total_limit=500.00, ...)

# Allocation budget — per-category caps, claimedCategory required
budget = client.create_budget(
    total_limit=500.00,
    allocations=[
        {"category": "flight", "limit": 300.00},
        {"category": "hotel",  "limit": 200.00},
    ]
)
# Anything outside flight/hotel → DENIED: NO_MATCHING_ALLOCATION
# Insurance attempt → DENIED: NO_MATCHING_ALLOCATION (not an error — a recorded decision)
```

**Denial codes** — structured, machine-readable, LLM-parseable

| Code | Recoverable? | Meaning |
|---|---|---|
| `INSUFFICIENT_FUNDS` | No | Budget or allocation has no remaining balance |
| `NO_MATCHING_ALLOCATION` | Maybe | Category not in budget's allocation list. Category matching is case-insensitive — `"flight"` and `"Flight"` both match |
| `MISSING_CLAIMED_CATEGORY` | Yes | Add `claimed_category` to the request |
| `ALLOCATION_EXHAUSTED` | Maybe | This allocation is at its limit — other allocations may have room |
| `BUDGET_PAUSED` | Maybe | Administratively paused — an admin can unpause |
| `BUDGET_CANCELLED` | No | Budget has been cancelled |
| `BUDGET_EXPIRED` | No | Budget past its expiry time |
| `EXCEEDS_TRANSACTION_LIMIT` | No | Single transaction over per-invoice ceiling |
| `ANOMALY_DETECTED` | No | Amount is statistically unusual — budget auto-paused, requires admin review |
| `ENTITY_ALREADY_AUTHORIZED` | No | Same real-world entity already has a live authorization |
| `DELEGATE_CAP_EXCEEDED` | No | Delegation token's per-agent cap exhausted — fleet may still have room |
| `DELEGATION_TOKEN_REVOKED` | No | Token was explicitly revoked by the orchestrator |

**Anomaly detection**

```python
# Default: budget auto-pauses on anomaly — requires human review to resume
budget = client.create_budget(total_limit=2000.00, anomaly_detection_enabled=True)

# Advisory mode: anomaly denies the request but keeps the budget ACTIVE
# Use for high-throughput fleets where one spike should not halt all agents
budget = client.create_budget(
    total_limit=2000.00,
    anomaly_detection_enabled=True,
    auto_pause_on_anomaly=False,
)
```

**Multi-agent causal chains**

When an orchestrator spawns sub-agents, link each sub-agent's spend back to the orchestrator's authorization with `parent_event_id`. FiGuard builds a full spend tree you can query later.

```python
# Orchestrator authorizes the overall task
orch_auth = client.authorize(
    session_token=orch_budget.session_token,
    agent_id="orchestrator",
    action_type="TASK",
    description="Book conference trip",
    requested_amount=500.00,
    idempotency_key="task-001",
)

# Sub-agents link their spend to the orchestrator event
flight_auth = client.authorize(
    session_token=sub_budget.session_token,
    agent_id="flight_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK",
    requested_amount=267.00,
    idempotency_key="flight-001",
    parent_event_id=orch_auth.event_id,  # causal link
)

hotel_auth = client.authorize(
    session_token=sub_budget.session_token,
    agent_id="hotel_agent",
    action_type="PURCHASE",
    description="Hotel — The Aster",
    requested_amount=198.00,
    idempotency_key="hotel-001",
    parent_event_id=orch_auth.event_id,  # same orchestrator event
)

# Retrieve the full spend tree — shows who spent what and why
tree = client.get_spend_tree(sub_budget.budget_id)
# tree.roots → [orchestrator event]
#   └── flight_agent: $267.00 CONFIRMED
#   └── hotel_agent:  $198.00 CONFIRMED
```

`parent_event_id` is audit-only — it does not affect enforcement. The tree is available via `client.get_spend_tree(budget_id)` and in the ledger response.

**Integration testing with dry_run**

```python
# Test your enforcement logic without writing to the ledger
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="test_agent",
    action_type="PURCHASE",
    description="Test: would this be allowed?",
    requested_amount=500.00,
    claimed_category="flight",
    idempotency_key="test-001",
    dry_run=True,   # runs all checks, returns decision, writes nothing
)
print(auth.decision)       # AUTHORIZED or DENIED
print(auth.denial_reason)  # populated if DENIED
# No ledger entry created, no webhooks fired, budget balance unchanged
```

**Budget lifecycle helpers**

```python
# Keep a long-running agent alive past its original expiry (max 24h at a time, repeatable)
client.extend_budget(budget_id, expires_in="2h")

# Cancel up to 100 budgets at once — already-terminal budgets included without error
client.cancel_batch(budget_ids=["bgt_1", "bgt_2", "bgt_3"])

# Idempotent budget creation — safe for orchestrator restarts
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    external_reference="run-abc-123",  # re-calling with same ref returns existing budget
)
```

**Idempotency**

Every `authorize()` call requires an `idempotency_key`. Retrying the same key returns the original decision — including the same denial code if it was denied. You can never double-spend by retrying a network timeout, and you never lose audit history by retrying a successful call.

FiGuard handles two distinct retry scenarios:

- **Same call retried** (network timeout, SDK retry): `idempotency_key` dedup — same decision returned, no second ledger entry written.
- **Same real-world entity attempted twice** (different agents, different sessions): `entity_id` dedup — second attempt returns `ENTITY_ALREADY_AUTHORIZED` with the original `event_id` attached.

---

## Why Not Just Use Stripe Spending Controls?

Stripe spending controls operate at payment time: the charge is attempted, then declined. FiGuard operates at decision time: the agent never attempts the charge if it will be denied.

The distinction matters because agents can take irreversible non-payment actions in response to an authorization (sending emails, booking calendars, calling third-party APIs). If FiGuard denies at decision time, none of that happens.

| | Stripe SPTs | OpenAI Spending Limits | FiGuard |
|---|---|---|---|
| Authorization timing | Payment time | Session level | Decision time |
| Structured denial response for agents | No | No | Yes — `denial_reason` + `denial_message` |
| Per-category allocation caps | No | No | Yes |
| Multi-agent causal chain tracking | No | No | Yes |
| Works for non-payment actions | No | No | Yes |
| Audit trail for every decision | Yes | No | Yes |
| Self-hostable | No | No | Yes |

---

## What This Looks Like When It Goes Wrong

A travel booking agent was given a $400 budget for a conference trip. The budget had no category caps — just a total limit. The agent successfully booked a $267 flight. Then it attempted to book travel insurance ($85), got a network timeout on the authorization call, and retried with a fresh idempotency key. The retry succeeded. Both the original and the retry were treated as separate authorizations. The agent then booked the hotel ($198), which pushed total reservations to $550 — $150 over the limit. The overspend slipped through because the timeout happened between the reservation write and the response, leaving a dangling authorization that wasn't visible to the agent.

Three things caused this: no idempotency on retries, no reservation-aware available-amount calculation, and no per-category caps that would have caught the insurance spend before it happened. FiGuard handles all three — idempotency is required on every call, available amount is calculated as `totalLimit − amountSpent − amountReserved` (so dangling reservations always reduce what's available), and allocation budgets enforce category caps before any funds are touched.

---

## Examples

| Example | What you learn |
|---|---|
| **[`examples/enforcement_cookbook.py`](examples/enforcement_cookbook.py)** | **Every enforcement capability in one runnable script — flat budgets, category allocations, STRICT mode, per-transaction ceiling, entity dedup, TraceId, resource budgets, CompositeGuard, and auto-expiry. Start here if you want to understand what FiGuard can enforce and how to configure it.** |
| [`examples/langchain-shopping-agent/`](examples/langchain-shopping-agent/) | How allocation budgets enforce category policy mid-run — flight and hotel authorized, insurance and dining denied with structured denial codes the LLM receives |
| [`examples/langchain-ap-agent/`](examples/langchain-ap-agent/) | How `ALLOCATION_EXHAUSTED` works when a batch of invoices drains a category mid-run, and how the agent handles partial approval |
| [`examples/crewai-research-fleet/`](examples/crewai-research-fleet/) | How three agents sharing a budget produce a causal spend tree, and how `parent_event_id` ties sub-agent spend back to orchestrator decisions |

---

## Fleet Agents

For concurrent sub-agent fleets, use delegation tokens: one shared fleet budget with fleet-wide allocation caps, one scoped token per sub-agent with per-agent caps. Sub-agents use delegation tokens identically to normal session tokens — enforcement is fully server-side.

```python
# Orchestrator: create the fleet budget once
fleet = client.create_budget(
    user_id="refund-fleet",
    total_limit=50_000,
    currency="USD",
    allocations=[
        {"category": "refund",     "limit": 50_000},
        {"category": "llm_tokens", "limit": 8_000_000},
    ],
)

# Issue one scoped token per sub-agent
token = client.create_delegation_token(
    budget_id=fleet.id,
    label="refund-agent-order-1001",
    caps=[
        {"category": "refund",     "limit": 3_000},   # per-agent cap
        {"category": "llm_tokens", "limit": 10_000},  # per-agent cap
    ],
)
session_token = token.session_token  # returned once — hand to sub-agent immediately

# Sub-agent: uses token exactly like a normal session token
result = client.authorize(
    session_token=session_token,
    agent_id="refund-agent-1001",
    action_type="REFUND",
    description="Order 1001 refund",
    requested_quantity=2_500,
    claimed_category="refund",
)
# FiGuard checks: per-agent cap ($3k) AND fleet cap ($50k) — both must pass

# Orchestrator: revoke when the agent's work is done
client.revoke_delegation_token(token.id)  # idempotent
```

FiGuard enforces the per-agent cap and the fleet-wide allocation in the same atomic transaction. The sub-agent never knows it's using a delegation token. Revocation is immediate — already-authorized events are unaffected.

---

## Self-Hosting

FiGuard is a Spring Boot service backed by PostgreSQL. Run it anywhere Docker runs.

```bash
# Start (includes Postgres)
make run

# Stop
make stop

# Reset (clears all data, regenerates demo key)
make reset
```

Production deployment — set these environment variables:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://your-db:5432/figuard
SPRING_DATASOURCE_USERNAME=figuard
SPRING_DATASOURCE_PASSWORD=your-password
```

**Requirements:** Docker, Docker Compose. No other dependencies.

**Stack:** Java 21, Spring Boot 3.5, PostgreSQL 15, Flyway.

---

## Project Structure

```
figuard-core/
├── src/                    # Java service (Spring Boot)
├── sdk/
│   ├── python/             # Python SDK — pip install figuard
│   │   └── figuard/
│   │       ├── client.py
│   │       ├── async_client.py
│   │       └── integrations/
│   │           ├── langchain.py
│   │           ├── crewai.py
│   │           ├── openai_agents.py
│   │           ├── openai.py
│   │           └── anthropic.py
│   ├── typescript/         # TypeScript SDK — npm install figuard
│   │   └── src/
│   │       ├── client.ts
│   │       └── models.ts
│   └── java/               # Java SDK — Maven Central
├── packages/
│   └── mcp/                # MCP server — npx figuard-mcp
│       └── src/
│           ├── index.ts
│           ├── tools.ts
│           └── handlers.ts
├── examples/
│   ├── enforcement_cookbook.py   # All enforcement capabilities — start here
│   └── ...                       # Framework-specific examples
└── docker-compose.yml
```

---

## SDK Support

| SDK | Status | Install |
|---|---|---|
| Python | ✅ Stable | `pip install figuard` |
| TypeScript / Node.js | ✅ Stable | `npm install figuard` |
| MCP Server | ✅ Stable | `npx figuard-mcp` |
| Java | ✅ Stable | Maven Central: `com.figuard:figuard-sdk` |
| Go | 📋 Planned | — |

The TypeScript SDK covers the same surface as the Python SDK with native `fetch` (no external dependencies) and full type definitions. The MCP server exposes all 13 tools to any MCP-compatible client (Claude Code, Cursor, Claude Desktop).

---

## Use With Claude Code or Cursor

Add FiGuard to your MCP config:

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "sb_live_demo",
        "FIGUARD_BASE_URL": "https://sandbox.figuard.io"
      }
    }
  }
}
```

Then ask your assistant: "Create a $500 travel budget with $300 for flights and $200 for hotels."

---

## Contributing

Issues, PRs, and integration requests welcome.

- [Contributing guide](CONTRIBUTING.md)
- [Good first issues](https://github.com/figuard/figuard-core/labels/good-first-issue)
- [GitHub Discussions](https://github.com/figuard/figuard-core/discussions)

Looking for contributors on: Go SDK · LlamaIndex integration · DSPy integration

---

## Why Not Build It Yourself?

You could. The authorize endpoint looks simple — check the balance, write a record. But the parts that matter are the parts that aren't obvious until you've hit them in production:

**Concurrent authorization** — two agents sharing a budget can both read the same available balance, both see enough funds, and both get approved. By the time the second write lands, you're over limit. The fix is a pessimistic write lock on the budget row during authorization. Easy to know, easy to forget.

**Dangling reservations** — a network timeout between the authorization write and the HTTP response leaves the agent with no event ID and the budget with a reserved amount it can't release. You need idempotency keyed to the request, not the response, so a retry finds the original authorization instead of creating a second one.

**The reservation/confirmation split** — if you use a single `amountSpent` field and deduct at authorization time, two concurrent authorizations both read the same balance before either writes. The correct model is two fields: `amountReserved` (deducted at authorization) and `amountSpent` (moved from reserved at confirmation). This is the two-phase reserve-then-capture pattern that payment processors use. It's not novel — it's just usually hidden inside Stripe.

**Session token security** — you need a token that scopes to exactly one budget, is returned exactly once, and is never stored in plaintext. If you store the raw token and your database is breached, every active agent session is compromised. Hash at write time, never store the raw value.

**Append-only ledger** — a mutable `status` field on an authorization record loses history. When you need to reconstruct what happened and why a budget hit its limit, you want every state transition recorded as a separate row, not an update to the previous one.

None of this is architecturally exotic. It's the same set of problems that payment infrastructure teams solved 20 years ago. FiGuard is that infrastructure applied to agent systems — already built, already tested, already handling the edge cases.

---

## Why This Exists

After years of building billing infrastructure and earlier years in enterprise software where transaction integrity and audit trails are non-negotiable, I kept noticing that AI agent code repeats failure modes that established financial systems learned to avoid years ago. Race conditions, retry storms, dangling authorizations, no audit trail. FiGuard applies the patterns that work for human-driven payments — pre-flight authorization, reservation lifecycle, append-only ledger — to agent-driven systems. It's not a new idea. It's the right idea for a new context.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
