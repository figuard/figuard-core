# FiGuard

A travel-booking agent hit a Stripe timeout. It retried. Then retried again. The customer's card was charged **three times for the same flight** before an engineer noticed the anomaly in the logs — 40 minutes later.

No alert fired. No limit existed. The agent had a valid API key and no concept of "I already did this."

FiGuard gives agents a budget. They ask permission before spending. You set the ceiling, the retry rules, and the idempotency policy once. Every spend attempt — authorized or denied — lands in an audit log.

Works with LangChain, CrewAI, LangGraph, and the OpenAI Agents SDK.

**Tested with:**

| Framework | Versions | Python |
|---|---|---|
| LangChain | ≥ 0.3.0 | 3.9 – 3.12 |
| LangGraph | ≥ 0.2.0 | 3.10 – 3.12 |
| CrewAI | ≥ 0.102 | 3.10 – 3.12 |
| OpenAI Agents SDK | ≥ 0.0.5 | 3.10 – 3.12 |
| TypeScript SDK | Node ≥ 18 | — |
| MCP server | Claude Code, Cursor, Claude Desktop | — |

[![CI](https://github.com/figuard/figuard-core/actions/workflows/ci.yml/badge.svg)](https://github.com/figuard/figuard-core/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-610%20passing-brightgreen)](#)
[![PyPI](https://img.shields.io/pypi/v/figuard)](https://pypi.org/project/figuard/)
[![npm](https://img.shields.io/npm/v/figuard?label=npm%20(ts-sdk)&color=cb3837)](https://www.npmjs.com/package/figuard)
[![npm](https://img.shields.io/npm/v/figuard-mcp?label=figuard-mcp&color=cb3837)](https://www.npmjs.com/package/figuard-mcp)

---

![FiGuard demo](https://github.com/user-attachments/assets/e953a132-c379-45fe-9796-644a4ec84c5d)

**Try it in 2 minutes — no setup required:**  
→ [Run the quickstart in Colab](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/01_infinite_loop.ipynb)  
→ [See the live dashboard](https://figuard-sandbox-g1ha.onrender.com/ui)

---

## 60-Second Quickstart

**1. Install and connect**

```bash
pip install figuard
```

```python
from figuard import FiGuardClient

# Zero-config — no setup required. Connects to the shared public sandbox automatically.
client = FiGuardClient()
```

The sandbox is a live FiGuard instance. Data is wiped periodically — not for production.
For production, [self-host FiGuard](https://figuard.io/docs/self-hosting) and set `FIGUARD_API_KEY` / `FIGUARD_BASE_URL`.

**2. Create a budget and authorize a spend**

```python
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
    authorization_expiry_seconds=300,
    intent_context="travel booking session",
)

auth = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK roundtrip",
    requested_quantity=270.00,
    idempotency_key="booking-001",
)

print(auth.decision)          # AUTHORIZED
print(auth.approved_quantity) # 270.0

# Confirm with actual charged amount after the transaction succeeds
client.confirm_event(auth.event_id, confirmed_quantity=267.00)

# Try a second spend that exceeds what's left ($500 - $267 = $233 remaining)
auth2 = client.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square 3 nights",
    requested_quantity=350.00,
    idempotency_key="hotel-001",
)

print(auth2.decision)       # DENIED
print(auth2.denial_reason)  # INSUFFICIENT_FUNDS
```

**3. See it in the dashboard**

Open the sandbox dashboard — your events are already there:

```
https://figuard-sandbox-g1ha.onrender.com/ui
```

Every authorization, denial, confirmation, and void shows up as a node in the spend tree in real time.

**4. Self-host** — [see self-hosting docs](docs/self-hosting.md)

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
docker compose up
# Ready at http://localhost:8080
```

That's it. The server is a Docker container — same as Postgres or Redis. You never need to touch the internals. Switch your client to localhost:

```python
from figuard import FiGuardClient

client = FiGuardClient(
    api_key="fg_live_demo",
    base_url="http://localhost:8080",  # or set FIGUARD_API_KEY + FIGUARD_BASE_URL
)
```

Run the example scenarios:

```bash
pip install figuard
python examples/rogue_agent_scenarios/demo.py
```

---

## How It Works

Four operations. Everything else is detail.

| Operation | What it does |
|---|---|
| `authorize()` | Agent asks permission — funds reserved, nothing moved yet |
| `confirm()` | Report what actually moved — releases the reservation |
| `void()` | Cancel a pending authorization — reservation released |
| `fail()` | Record a failed action — reservation released |

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

Every authorization, denial, confirmation, and void is a row in the ledger. The spend tree shows the full causal chain across an orchestrator and its sub-agents:

![FiGuard Spend Tree — orchestrator with confirmed and denied sub-agent events](docs/spend-tree.png)

---

## Why Not Build It Yourself?

You could. The authorize endpoint looks simple — check the balance, write a record. But the parts that matter are the parts that aren't obvious until you've hit them in production:

**Concurrent authorization** — two agents sharing a budget can both read the same available balance, both see enough funds, and both get approved. By the time the second write lands, you're over limit. The fix is a pessimistic write lock on the budget row during authorization. Easy to know, easy to forget.

**Dangling reservations** — a network timeout between the authorization write and the HTTP response leaves the agent with no event ID and the budget with a reserved amount it can't release. You need idempotency keyed to the request, not the response, so a retry finds the original authorization instead of creating a second one.

**The reservation/confirmation split** — if you use a single `amountSpent` field and deduct at authorization time, two concurrent authorizations both read the same balance before either writes. The correct model is two fields: `amountReserved` (deducted at authorization) and `amountSpent` (moved from reserved at confirmation). This is the two-phase reserve-then-capture pattern that payment processors use. It's not novel — it's just usually hidden inside Stripe.

**Session token security** — you need a token that scopes to exactly one budget, is returned exactly once, and is never stored in plaintext. If you store the raw token and your database is breached, every active agent session is compromised. Hash at write time, never store the raw value.

**Append-only ledger** — a mutable status field on an authorization record loses history. When you need to reconstruct what happened and why a budget hit its limit, you want every state transition recorded as a separate row, not an update to the previous one.

None of this is architecturally exotic. It's the same set of problems that payment infrastructure teams solved 20 years ago. FiGuard is that infrastructure applied to agent systems — already built, already tested, already handling the edge cases.

---

## Create Your First Policy

Pick your scenario in the interactive wizard — monetary vs non-monetary budget, single agent vs fleet, per-category limits, safety controls — and get the exact `create_budget` + `authorize` calls ready to paste.

**[→ Open the code wizard](https://figuard.io/#get-started)**

Or follow the decision tree in [Pick your pattern](docs/pick-your-pattern.md) if you prefer plain docs. Full parameter reference in [Budget configuration](docs/budget-configuration.md).

---

## Examples

### FiGuard in your stack

Real failure modes that happen inside LangChain, LangGraph, and CrewAI — each showing the problem and the fix side-by-side. Run in simulation mode with no API keys, or switch to real mode with your own keys.

| Framework | Failure mode | FiGuard stops it at | Colab |
|---|---|---|---|
| **LangChain** | Payment tool times out after Stripe charges. Retry = double charge. | Idempotency key collapses retry to one event — Stripe skipped | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/01_langchain_payment_retry.ipynb) |
| **LangGraph** | Research loop runs 30 iterations on an ambiguous query. LLM controls the exit — `max_iterations` doesn't bound cost. | Budget ceiling at $0.20 — loop exits at iteration 20 | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/02_langgraph_research_loop.ipynb) |
| **LangGraph** | Supervisor routes a task through three sub-agents. Researcher runs up cost — shared budget has no attribution. | Delegation token per agent — researcher capped, billing and writer complete | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/03_langgraph_supervisor_fleet.ipynb) |
| **CrewAI** | Parallel crew — market researcher makes 25 data API calls on a 5-call task. No per-agent visibility. | Delegation token per crew member — researcher capped, analyst and writer unaffected | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/04_crewai_parallel_crew.ipynb) |

Source: [`examples/framework_scenarios/`](examples/framework_scenarios/)

```bash
pip install figuard
python examples/framework_scenarios/langchain_payment_retry.py      # no keys needed
python examples/framework_scenarios/langgraph_research_loop.py
python examples/framework_scenarios/langgraph_supervisor_fleet.py
python examples/framework_scenarios/crewai_parallel_crew.py
```

---

### Agent Failure Scenarios

Five lower-level failure modes — idempotency, concurrent overspend, rogue sub-agents, category violations.

| # | Scenario | FiGuard stops it at | Colab |
|---|----------|---------------------|-------|
| 1 | **Infinite quality loop** — 847 iterations, $16.94, no alert | iteration 251, $5.00 budget ceiling | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/01_infinite_loop.ipynb) |
| 2 | **Duplicate invoice payment** — timeout + retry = double charge | retry returns same event_id, one charge | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/02_duplicate_payment.ipynb) |
| 3 | **Concurrent fleet overspend** — 10 agents, 1 budget, $2k attempted | 5 authorized, 5 denied, $1k never exceeded | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/03_concurrent_overspend.ipynb) |
| 4 | **Rogue sub-agent** — one hallucinating agent drains the whole fleet | delegation cap stops researcher at $200, fleet completes | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/04_rogue_subagent_fleet.ipynb) |
| 5 | **Category violation** — hotel charged to flight budget, found at month-end | `DENIED — NO_MATCHING_ALLOCATION` at authorization time | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/05_category_violation.ipynb) |

Source: [`examples/rogue_agent_scenarios/`](examples/rogue_agent_scenarios/)

---

## What FiGuard Is Not

**Not a payment processor.** FiGuard never touches money. It authorizes the intent to spend and records the decision. The actual payment goes through your existing processor as before.

**Not a policy language.** Budget limits and allocation caps are structured data, not a DSL. FiGuard matches the category an agent declares against the categories you defined — nothing more.

**Not a firewall for human users.** FiGuard is purpose-built for agent-to-service authorization. The session token model assumes agents are ephemeral and untrusted by default.

**Not a replacement for Stripe spending controls.** Use both if you want defense in depth. FiGuard blocks at agent decision time; Stripe blocks at payment time. Different attack surfaces.

**Not a security boundary against adversarial agents.** FiGuard enforces what the agent declares. An agent that lies about its category or amount bypasses category enforcement. FiGuard is designed for honest agents with bounded resources — the same threat model as a database connection pool or a rate limiter. It prevents accidental overspend and enforces organizational policies on well-behaved agents. For adversarial agent containment, pair FiGuard with a security layer like [Microsoft AGT](https://github.com/microsoft/agt).

---

## Docs

- **Interactive API docs:** http://localhost:8080/swagger-ui/index.html (local) · https://figuard-sandbox-g1ha.onrender.com/swagger-ui/index.html (live sandbox)
- [API Reference](docs/api-reference.md) — full endpoint reference with payloads
- [Pick Your Pattern](docs/pick-your-pattern.md) — decision tree: find your scenario, get the exact create + authorize calls
- [Budget Configuration](docs/budget-configuration.md) — full parameter reference for all four configuration layers
- [Framework Integrations](docs/integrations.md) — LangChain, CrewAI, OpenAI Agents SDK, Anthropic
- [Fleet Agents & Delegation Tokens](docs/fleet-agents.md)
- [Enforcement Features](docs/enforcement.md) — denial codes, anomaly detection, allocation modes
- [Replay & Audit](docs/replay.md)
- [TypeScript SDK](docs/typescript-sdk.md)
- [MCP Server](docs/mcp-server.md)
- [Self-Hosting](docs/self-hosting.md)
- [Known Limitations](docs/known-limitations.md)

---

## SDKs

| SDK | Install |
|---|---|
| Python | `pip install figuard` |
| TypeScript / Node.js | `npm install figuard` |
| MCP Server | `npx figuard-mcp` |
| Java | `com.figuard:figuard-sdk` |

---

## Roadmap

### V2
- **Row Level Security** — PostgreSQL RLS policies as a second enforcement layer for operators running FiGuard in shared multi-tenant mode
- **Velocity counter table** — replace live `COUNT`/`SUM` scans in the current velocity controls with an atomically-incremented counter table per budget per window, removing the `spend_events` scan from the authorize hot path at scale
- **Overdraft policies** — per-budget policy flag controlling behaviour when budget is exhausted: `REJECT` (current default), `ALLOW_IF_AVAILABLE` (use remaining funds, deny the rest), `ALLOW_WITH_OVERDRAFT` (permit and record overdraft for later settlement)

### V3
- **Redis velocity counters** — sliding-window counters in Redis, eliminating the DB round-trip for velocity checks at high throughput
- **Helm chart** — production-grade Kubernetes deployment with configurable replicas, resource limits, and secret management

---

## Contributing

Issues, PRs, and integration requests welcome.

- [Contributing guide](CONTRIBUTING.md)
- [Good first issues](https://github.com/figuard/figuard-core/labels/good-first-issue)
- [GitHub Discussions](https://github.com/figuard/figuard-core/discussions)

Looking for contributors on: Go SDK · LlamaIndex integration · DSPy integration · Helm chart

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
