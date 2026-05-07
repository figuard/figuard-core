# FiGuard

**Pre-flight spend authorization for AI agents.**  
Your agent asks permission before money moves. FiGuard says yes or no — and keeps a complete audit trail either way.  
Stop runaway agent purchases before they happen.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![PyPI](https://img.shields.io/pypi/v/figuard)](https://pypi.org/project/figuard/)
[![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)](https://www.python.org/)

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

# Create a budget for this agent session
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    expires_at="2026-06-01T00:00:00Z",
    intent_context="travel booking session",
)

# Pre-authorize before any spend
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK roundtrip",
    requested_amount=267.00,
    idempotency_key="booking-001",
)

print(auth.decision)        # AUTHORIZED
print(auth.approved_amount) # 267.0

# After the real transaction succeeds
client.confirm_event(auth.event_id, confirmed_amount=267.00)
```

---

## What FiGuard Is Not

**Not a payment processor.** FiGuard never touches money. It authorizes the *intent to spend* and records the decision. The actual payment goes through your existing processor (Stripe, Braintree, etc.) as before.

**Not a policy language.** Budget limits and allocation caps are structured data, not a DSL. FiGuard matches the category an agent declares against the categories you defined — nothing more.

**Not a firewall for human users.** FiGuard is purpose-built for agent-to-service authorization. The session token model assumes agents are ephemeral and untrusted by default.

**Not a replacement for Stripe spending controls.** Use both if you want defense in depth. FiGuard blocks at agent decision time; Stripe blocks at payment time. Different attack surfaces.

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

## How It Works

```
                                           ┌──────────────────────────────┐
  Agent wants to spend                     │  Decision recorded in ledger  │
  ──────────────────────────▶  FiGuard ────│  (approved or denied)         │
  (nothing has moved yet)                  └──────────────────────────────┘
          │
          │  AUTHORIZED                             DENIED
          │  funds reserved                         nothing moves
          │                                         agent gets structured response
          ▼
  [your payment processor]
          │
          │  payment succeeds ──▶ confirm()  → reservation → confirmed spend
          │  payment fails    ──▶ fail()     → reservation released
          │  action cancelled ──▶ void()     → reservation released
          │
          ▼
  Decision finalized in ledger
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
    amount_key="price",   # kwarg that holds the spend amount
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
    amount_key="price",
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
    amount_key="price",
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
    amount_key="price",
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
| `NO_MATCHING_ALLOCATION` | Maybe | Category not in budget's allocation list — try a different category |
| `MISSING_CLAIMED_CATEGORY` | Yes | Add `claimed_category` to the request |
| `ALLOCATION_EXHAUSTED` | Maybe | This allocation is at its limit — other allocations may have room |
| `BUDGET_PAUSED` | Maybe | Administratively paused — an admin can unpause |
| `BUDGET_CANCELLED` | No | Budget has been cancelled |
| `BUDGET_EXPIRED` | No | Budget past its expiry time |
| `EXCEEDS_TRANSACTION_LIMIT` | No | Single transaction over per-invoice ceiling |
| `ANOMALY_DETECTED` | No | Amount is statistically unusual — budget auto-paused, requires admin review |
| `ENTITY_ALREADY_AUTHORIZED` | No | Same real-world entity already has a live authorization |

**Anomaly detection**

```python
budget = client.create_budget(
    total_limit=2000.00,
    anomaly_detection_enabled=True,
    # If a request exceeds mean × 3 (after 5+ samples), budget auto-pauses
)
```

**Multi-agent causal chains**

```python
# Sub-agent links its spend to the orchestrator's authorization
auth = client.authorize(
    ...,
    parent_event_id=orchestrator_event.event_id,  # causal link
)
# spend_tree shows exactly which agent triggered which spend
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
| [`examples/langchain-shopping-agent/`](examples/langchain-shopping-agent/) | How allocation budgets enforce category policy mid-run — flight and hotel authorized, insurance and dining denied with structured denial codes the LLM receives |
| [`examples/langchain-ap-agent/`](examples/langchain-ap-agent/) | How `ALLOCATION_EXHAUSTED` works when a batch of invoices drains a category mid-run, and how the agent handles partial approval |
| [`examples/crewai-research-fleet/`](examples/crewai-research-fleet/) | How three agents sharing a budget produce a causal spend tree, and how `parent_event_id` ties sub-agent spend back to orchestrator decisions |

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
│   └── java/               # Java SDK — Maven Central
├── examples/               # Runnable examples
└── docker-compose.yml
```

---

## Contributing

Issues, PRs, and integration requests welcome.

- [Contributing guide](CONTRIBUTING.md)
- [Good first issues](https://github.com/figuard/figuard-core/labels/good-first-issue)
- [GitHub Discussions](https://github.com/figuard/figuard-core/discussions)

Looking for contributors on: TypeScript SDK · Go SDK · LlamaIndex integration · Vercel AI SDK integration · DSPy integration

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
