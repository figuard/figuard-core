# FiGuard

**Pre-flight spend authorization for AI agents.**  
Your agent asks permission before money moves. FiGuard says yes or no — and keeps a complete audit trail either way.  
Stop runaway agent purchases before they happen.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
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

### LangChain

```python
from figuard.integrations.langchain import FiGuardCallbackHandler

agent = create_react_agent(
    llm=llm,
    tools=tools,
    callbacks=[FiGuardCallbackHandler(client=client, session_token=budget.session_token)]
)
# Every tool call is now pre-authorized. Denied calls never execute.
```

### CrewAI

```python
from figuard.integrations.crewai import FiGuardCrewGuard

guard = FiGuardCrewGuard(client=client, session_token=budget.session_token)
result = guard.wrap(my_crew).kickoff()
```

### OpenAI Agents SDK

```python
from figuard.integrations.openai_agents import figuard_guard

result = await figuard_guard(
    Runner.run(agent, "Book a flight to NYC"),
    client=client,
    session_token=budget.session_token,
)
```

### Raw OpenAI Function Calling

```python
from figuard.integrations.openai import guarded_function

@guarded_function(category="purchase", client=client, session_token=budget.session_token)
def charge_customer(amount: float, vendor: str) -> str:
    return stripe.charge(amount, vendor)
# FiGuard authorizes before the function runs. Denied = function never executes.
```

### Anthropic (Claude tool use)

```python
from figuard.integrations.anthropic import FiGuardAnthropicGuard

guard = FiGuardAnthropicGuard(client=client, session_token=budget.session_token)
# Wraps tool_use blocks — authorization happens before each tool call
```

Install with framework extras:

```bash
pip install figuard[langchain]       # LangChain + LangGraph
pip install figuard[crewai]          # CrewAI
pip install figuard[openai-agents]   # OpenAI Agents SDK
pip install figuard[openai]          # Raw OpenAI function calling
pip install figuard[anthropic]       # Anthropic tool use
pip install figuard[all]             # Everything
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

## Why This Exists

After years of building billing infrastructure and earlier years in enterprise software where transaction integrity and audit trails are non-negotiable, I kept noticing that AI agent code repeats failure modes that established financial systems learned to avoid years ago. Race conditions, retry storms, dangling authorizations, no audit trail. FiGuard applies the patterns that work for human-driven payments — pre-flight authorization, reservation lifecycle, append-only ledger — to agent-driven systems. It's not a new idea. It's the right idea for a new context.

---

## License

MIT — see [LICENSE](LICENSE).
