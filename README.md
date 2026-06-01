<img src="docs/logo.svg" alt="FiGuard" height="44" />

[![CI](https://github.com/figuard/figuard-core/actions/workflows/ci.yml/badge.svg)](https://github.com/figuard/figuard-core/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-0A5C38.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-610%20passing-0A5C38)](#)
[![PyPI](https://img.shields.io/pypi/v/figuard?color=0A5C38)](https://pypi.org/project/figuard/)
[![npm](https://img.shields.io/npm/v/figuard?label=npm%20(ts-sdk)&color=0A5C38)](https://www.npmjs.com/package/figuard)
[![npm](https://img.shields.io/npm/v/figuard-mcp?label=figuard-mcp&color=0A5C38)](https://www.npmjs.com/package/figuard-mcp)

---

A travel-booking agent hit a Stripe timeout. It retried. Then retried again. The customer's card was charged **three times for the same flight** before an engineer noticed the anomaly in the logs — 40 minutes later.

No alert fired. No limit existed. The agent had a valid API key and no concept of "I already did this."

FiGuard gives agents a budget. They ask permission before spending. You set the ceiling, the retry rules, and the idempotency policy once. Every spend attempt — authorized or denied — lands in an audit log.

Your framework decides what to do next. FiGuard decides whether the resource-consuming action is allowed.

```
  Your agent code  (LangChain · LangGraph · CrewAI · any runtime)
  orchestrates — decides what to do next
          ↓  agent wants to spend / call / execute
  figuard.authorize()
  checks: limit · category · velocity · dedup
          ↓  AUTHORIZED — action proceeds
  Stripe · OpenAI · any API or service
  executes — real money or resource consumed
          ↓  action completes
  figuard.confirm()
  settles reservation — ledger updated
```

![FiGuard demo](https://github.com/user-attachments/assets/e953a132-c379-45fe-9796-644a4ec84c5d)

**Try it now — no setup, no signup:**  
→ [Run in Colab](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/01_infinite_loop.ipynb)  
→ [Live dashboard](https://figuard-sandbox-g1ha.onrender.com/ui)  
→ [View on GitHub](https://github.com/figuard/figuard-core)

> FiGuard is the authorization and ledger layer — not a payment processor, not a policy DSL, not an adversarial-agent firewall. [Full scope →](#what-figuard-is-not)

---

## Quickstart

**Tested with:**

| Framework | Versions | Python |
|---|---|---|
| LangChain | ≥ 0.3.0 | 3.9 – 3.12 |
| LangGraph | ≥ 0.2.0 | 3.10 – 3.12 |
| CrewAI | ≥ 0.102 | 3.10 – 3.12 |
| OpenAI Agents SDK | ≥ 0.0.5 | 3.10 – 3.12 |
| TypeScript SDK | Node ≥ 18 | — |
| MCP server | Claude Code, Cursor, Claude Desktop | — |

```bash
pip install figuard
```

```python
from figuard import FiGuardClient

# Zero-config — connects to the shared public sandbox automatically.
# For production: set FIGUARD_API_KEY + FIGUARD_BASE_URL, or see Self-Hosting below.
client = FiGuardClient()

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

# Confirm with actual charged amount — may differ from requested (taxes, FX, discounts)
client.confirm_event(auth.event_id, confirmed_quantity=267.00)

# Second spend — exceeds what's left ($500 - $267 = $233 remaining)
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

Every authorization, denial, and confirmation shows up in the spend tree at `https://figuard-sandbox-g1ha.onrender.com/ui` in real time.

Not sure what limits to set? Add `trust_mode="SHADOW"` to `create_budget` — all checks run, nothing is blocked, and `auth.would_have_been` tells you what would have happened. When the limits look right, switch to enforcement without recreating the budget: `client.update_budget(budget.id, trust_mode="FULL_ENFORCEMENT")`.

---

## How It Works

Four operations. Everything else is detail.

| Operation | What it does |
|---|---|
| `authorize()` | Agent asks permission — capacity reserved, nothing moved yet |
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

A budget issues session tokens. An agent's `authorize` call reserves capacity. Execution happens externally — FiGuard never sees the data, never proxies the call. The agent reports back via `confirm`, `void`, or `fail`. Every state transition lands in the append-only ledger. The spend tree shows the full causal chain across an orchestrator and its sub-agents:

![FiGuard Spend Tree — orchestrator with confirmed and denied sub-agent events](docs/spend-tree.png)

---

## The Hard Parts

The authorize endpoint looks simple — check the balance, write a record. The parts that matter aren't obvious until you've hit them in production:

**Concurrent authorization** — two agents sharing a budget can both read the same available balance, both see enough funds, and both get approved. By the time the second write lands, you're over limit. The fix is a pessimistic write lock on the budget row during authorization. Easy to know, easy to forget.

**Dangling reservations** — a network timeout between the authorization write and the HTTP response leaves the agent with no event ID and the budget with a reserved amount it can't release. You need idempotency keyed to the request, not the response, so a retry finds the original authorization instead of creating a second one.

**The reservation/confirmation split** — if you use a single `amountSpent` field and deduct at authorization time, two concurrent authorizations both read the same balance before either writes. The correct model is two fields: `amountReserved` (deducted at authorization) and `amountSpent` (moved from reserved at confirmation). This is the two-phase reserve-then-capture pattern that payment processors use. It's not novel — it's just usually hidden inside Stripe.

**Session token security** — you need a token that scopes to exactly one budget, is returned exactly once, and is never stored in plaintext. If you store the raw token and your database is breached, every active agent session is compromised. Hash at write time, never store the raw value.

**Append-only ledger** — a mutable status field on an authorization record loses history. When you need to reconstruct what happened and why a budget hit its limit — or when a finance team asks why $40K of agent spend happened last Tuesday — you want every state transition as a separate row, not an update to the previous one.

These are the same problems payment infrastructure teams solved 20 years ago. The reserve-then-confirm pattern, idempotency keyed to the request, append-only ledger — none of it is novel. FiGuard is that infrastructure applied to agent systems.

---

## Failure Scenarios

These are failure modes that logging and observability tools can't catch — they require enforcement at authorization time. Each has a Colab to run with no API keys needed.

Notebooks live in [figuard-notebooks](https://github.com/figuard/figuard-notebooks); each runs in Colab with no API keys required.

| Scenario | Framework | Failure mode | FiGuard stops it at | Colab |
|---|---|---|---|---|
| **Payment retry storm** | LangChain | Tool times out after Stripe charges. Retry = double charge. | Idempotency key — retry returns the same event, Stripe never called twice | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/01_langchain_payment_retry.ipynb) |
| **Research cost spiral** | LangGraph | Loop runs 30 iterations on an ambiguous query. LLM controls the exit. | Budget ceiling at $0.20 — loop exits at iteration 20 | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/02_langgraph_research_loop.ipynb) |
| **Fleet attribution loss** | LangGraph | Supervisor routes through 3 sub-agents. No per-agent cost caps. | Delegation token per agent — researcher capped, others unaffected | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/03_langgraph_supervisor_fleet.ipynb) |
| **Parallel crew blowout** | CrewAI | Parallel crew — one member makes 25 API calls on a 5-call task | Delegation cap stops the runaway member, rest of crew completes | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/framework-scenarios/04_crewai_parallel_crew.ipynb) |
| **Concurrent overspend** | Any | 10 agents share one budget. All read the same balance simultaneously. | Pessimistic lock — 5 authorized, 5 denied, $1k ceiling never exceeded | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/03_concurrent_overspend.ipynb) |
| **Category violation** | Any | Hotel charged to flight budget. Found at month-end. | `DENIED — NO_MATCHING_ALLOCATION` at authorization time | [![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/figuard/figuard-notebooks/blob/main/agent-incidents/05_category_violation.ipynb) |

Source: [`examples/framework_scenarios/`](examples/framework_scenarios/) · [`examples/rogue_agent_scenarios/`](examples/rogue_agent_scenarios/)

```bash
pip install figuard
python examples/framework_scenarios/langchain_payment_retry.py      # no API keys needed
python examples/framework_scenarios/langgraph_research_loop.py
python examples/framework_scenarios/langgraph_supervisor_fleet.py
python examples/framework_scenarios/crewai_parallel_crew.py
```

---

## What FiGuard Is Not

**Not a payment processor.** FiGuard never touches money. It authorizes the intent to spend and records the decision. The actual payment goes through your existing processor as before.

**Not a policy language.** Budget limits and allocation caps are structured data, not a DSL. FiGuard matches the category an agent declares against the categories you defined — nothing more.

**Not a firewall for human users.** FiGuard is purpose-built for agent-to-service authorization. The session token model assumes agents are ephemeral and untrusted by default.

**Not a replacement for Stripe spending controls.** Use both if you want defense in depth. FiGuard blocks at agent decision time; Stripe blocks at payment time. Different layers.

**Not a security boundary against adversarial agents.** FiGuard enforces what the agent declares. An agent that lies about its category or amount bypasses category enforcement. FiGuard is designed for honest agents with bounded resources — the same threat model as a database connection pool or a rate limiter. It prevents accidental overspend and enforces organizational policies on well-behaved agents. For adversarial agent containment, pair FiGuard with a security layer like [Microsoft AGT](https://github.com/microsoft/agt).

Observability tools record what happened after execution. LLM gateways manage model routing and token spend. FiGuard is the enforcement layer — it authorizes before any action executes, across the full resource spectrum. They complement each other.

---

## Self-Hosting

FiGuard is a single Docker container alongside your existing infrastructure — same as adding Postgres or Redis. Your spend data never leaves your environment.

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
docker compose up -d
# Ready at http://localhost:8080
```

Point your client at it:

```python
client = FiGuardClient(
    api_key="your_api_key",
    base_url="http://localhost:8080",
)
```

Full setup guide, environment variables, Postgres configuration, and production checklist: [Self-Hosting](docs/self-hosting.md).

---

## Performance

Authorize calls complete in under 10ms p99 on a local deployment with a warm connection pool. The authorize path acquires a pessimistic row lock, writes the reservation, and returns — there is no external network hop. In practice the overhead is smaller than a typical database query in your existing stack.

For production benchmarks, run the self-hosted Docker setup against a local Postgres instance — the sandbox is an evaluation environment, not a performance reference.

---

## Docs

**Start here:**
- [API Reference](docs/api-reference.md) — full endpoint reference with payloads
- [Pick Your Pattern](docs/pick-your-pattern.md) — decision tree: find your scenario, get exact code
- [Framework Integrations](docs/integrations.md) — LangChain, CrewAI, OpenAI Agents SDK, Anthropic
- [Self-Hosting](docs/self-hosting.md) — Docker, Postgres, production checklist

**Reference:**
- [Budget Configuration](docs/budget-configuration.md) — full parameter reference for all configuration layers
- [Enforcement Features](docs/enforcement.md) — denial codes, anomaly detection, allocation modes
- [Fleet Agents & Delegation Tokens](docs/fleet-agents.md)
- [Handling Denials](docs/denial-handling.md) — per-code recovery strategies, LLM prompt instructions
- [Audit & Replay](docs/audit-replay.md) — ledger, point-in-time snapshots, timeline, counterfactual
- [Webhooks](docs/webhooks.md) — event types, registration, signature verification
- [Observability](docs/integrations/observability.md) — FiGuard spans in Langfuse, Jaeger, Honeycomb, Datadog
- [TypeScript SDK](docs/typescript-sdk.md)
- [MCP Server](docs/integrations/mcp.md)
- [Cookbook](docs/cookbook.md) — short recipes: authorize/confirm/void, parallel calls, causal chains, testing
- [Known Limitations](docs/known-limitations.md)

Interactive API docs: [localhost:8080/swagger-ui](http://localhost:8080/swagger-ui/index.html) · [sandbox](https://figuard-sandbox-g1ha.onrender.com/swagger-ui/index.html)

---

## SDKs

| SDK | Install |
|---|---|
| Python | `pip install figuard` |
| TypeScript / Node.js | `npm install figuard` |
| MCP Server | `npx figuard-mcp` |
| Java | `com.figuard:figuard-sdk:0.3.0` |

---

## Roadmap

- **Scoped tokens** — derived session tokens with hard restrictions on action types, categories, and max transaction amount; for untrusted sub-agent delegation
- **Overdraft policies** — per-budget `REJECT` / `ALLOW_IF_AVAILABLE` / `ALLOW_WITH_OVERDRAFT` modes

See [ROADMAP.md](ROADMAP.md) for the full list.

---

## Versioning

FiGuard is pre-1.0. Patch releases may include breaking changes to the API or SDK. Stable guarantees begin at v1.0.

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
