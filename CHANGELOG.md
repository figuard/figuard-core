# Changelog

All notable changes to FiGuard are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

## [v1.1.1] — 2026-06-10 — Security hardening

Hardening pass focused on the credential and webhook surfaces. No API changes —
existing integrations are unaffected.

### Credential hashing — HMAC with a server-side pepper
API keys and session tokens are now stored as HMAC-SHA256(credential, pepper) when
`FIGUARD_TOKEN_PEPPER` is set (falls back to SHA-256 when unset, for local/dev). The pepper
lives in the environment, never in the database — so a database-only breach yields hashes
that cannot be verified offline.

### Webhook SSRF protection
Outbound webhook URLs are validated at both registration and delivery time. Requests to
loopback, private, link-local (including cloud metadata endpoints), and multicast addresses
are rejected; https is required; redirects are disabled. DNS is re-resolved at send time to
defeat rebinding.

### Secure-by-default secrets for self-hosters
On first boot, the container generates and persists a unique credential pepper and
webhook-encryption key to the data volume if they are not provided — every self-hosted
install gets its own stable secrets with zero configuration. Explicit env vars take
precedence.

### Supply-chain scanning
Dependabot (weekly) and Trivy (filesystem + container image) now run in CI, with results in
the GitHub Security tab.

### Versions
Server / Docker image patched. SDK packages republished at 1.1.1 with no code changes.

---

## [v1.1.0] — 2026-06-07 — auto_guard one-liners + figuard-langchain

### New: `auto_guard_langchain` and `auto_guard_crewai` one-liners

Zero-config wrappers that create a budget, wire the callback handler, and return the
executor in a single call. Works against the shared public sandbox by default.

```python
from figuard.integrations.langchain import auto_guard_langchain
executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)

from figuard.integrations.crewai import auto_guard_crewai
auto_guard_crewai(my_tool, budget=500, velocity_max_per_minute=10)
```

Both wrappers now accept `velocity_max_per_minute` — catches runaway loops even for
agents whose tools carry no dollar amount (research agents, code agents, etc.).

`auto_guard_langchain` and `auto_guard_crewai` are now exported from the top-level
`figuard` namespace: `from figuard import auto_guard_langchain`.

### New package: `figuard-langchain`

Standalone PyPI package for LangChain-specific installs:

```bash
pip install figuard-langchain
```

Re-exports `FiGuardCallbackHandler`, `FiGuardToolGuard`, and `auto_guard_langchain`
from a dedicated package with its own PyPI search presence.

### Bug fix: `BudgetNotFoundException` returns 404 not 500

`GET /budgets/{id}` with a non-existent or expired budget ID now returns a clean
`404 Not Found` with `"Budget not found: {id}"`. Previously returned `500 Internal
Server Error`, making it impossible for clients to distinguish "server error" from
"this budget doesn't exist."

### Python SDK: 0.5.0 → 1.1.0 · TypeScript SDK: 1.0.0 → 1.1.0 · MCP: 1.0.0 → 1.1.0

---

## [v1.0.0] — 2026-06-02 — Initial OSS Release

### Core authorization engine
- Pre-flight spend authorization: `POST /api/v1/authorize` with `X-Session-Token` header
- Budget creation with optional per-category allocations (`POST /api/v1/budgets`)
- Event lifecycle: confirm, fail, void (`POST /api/v1/events/{id}/confirm|fail|void`)
- Idempotent budget creation via `externalReference`
- Pessimistic locking throughout — no concurrent overdraw under any load
- `AUTHORIZED` / `DENIED` with 15 denial codes (BUDGET_EXHAUSTED, ALLOCATION_EXHAUSTED, VELOCITY_LIMIT_EXCEEDED, INTENT_SCOPE_VIOLATION, DELEGATE_CAP_EXCEEDED, and more)

### Budget management
- Extend budget expiry (`POST /budgets/{id}/extend`)
- Fund / adjust budget limit in-place: CREDIT, DEBIT, RESET, RESET_SPENT (`POST /budgets/{id}/fund`)
- Cancel single or batch (`POST /budgets/{id}/cancel`, `POST /budgets/cancel-batch`)
- Resume paused budget with required override reason (`POST /budgets/{id}/resume`)
- Rotate session token with grace period (`POST /budgets/{id}/rotate-token`)
- Paginated budget list with status and userId filters

### Velocity controls
- `velocityMaxPerMinute`, `velocityMaxAmountPerHour`, `velocityMaxPerDay` per budget
- Rolling-window enforcement inside the authorization pessimistic lock
- First violation fires `VELOCITY_LIMIT_EXCEEDED` webhook; subsequent denials in window are silent
- Configurable via `PATCH /budgets/{id}` and MCP `figuard_create_budget` tool

### Anomaly detection
- Configurable threshold multiplier per budget (`anomalyDetectionEnabled`, `anomalyPauseThreshold`)
- Advisory mode (log + webhook, budget stays ACTIVE) or auto-pause mode
- `ANOMALY_DETECTED` webhook fires on first crossing

### Delegation tokens
- Fleet budget → sub-agent scoped tokens with per-category caps
- Delegation cap enforced on both flat and allocated budgets (pessimistic lock)
- `POST /budgets/{id}/delegation-tokens`, list, get, revoke
- `DELEGATION_TOKEN_CREATED` / `DELEGATION_TOKEN_REVOKED` webhooks

### Spend replay & audit
- Full event replay with projected state snapshots (`GET /budgets/{id}/replay`)
- Point-in-time state projection (`GET /budgets/{id}/replay/state`)
- Lightweight timeline (`GET /budgets/{id}/replay/timeline`)
- What-if analysis — test a hypothetical policy against real history (`POST /budgets/{id}/replay/counterfactual`)

### Webhooks
- 10-attempt exponential backoff retry sweep (ShedLock prevents duplicate runs)
- Manual retry endpoint (`POST /webhooks/deliveries/{id}/retry`)
- Webhook secrets encrypted at rest (AES-256-GCM)
- 19 event types including velocity, anomaly, delegation, and lifecycle events
- HMAC-SHA256 signatures on all deliveries

### Receipts
- Shareable public receipt URL per budget (`GET /budgets/{id}/receipt`)
- HTML receipt page — no auth required, 90-day TTL (`GET /receipts/{token}`)

### Zero-config developer experience
- `FiGuardClient()` requires no arguments — resolves: explicit params → `FIGUARD_API_KEY`/`FIGUARD_BASE_URL` env vars → shared public sandbox (`sb_live_demo`)
- One-time sandbox warning on fallback; suppressable via `FIGUARD_SUPPRESS_SANDBOX_WARNING=1`
- TypeScript SDK: equivalent zero-config constructor
- `auto_guard_langchain(executor, budget=500)` — one-liner: creates client + 24h budget + wires `FiGuardCallbackHandler`
- `auto_guard_crewai(tool, budget=500)` — one-liner: creates client + 24h budget + wraps tool via `FiGuardCrewGuard`

### External events + fail-open reconciliation
- `POST /api/v1/events/external` — record a spend that happened outside FiGuard (outage reconciliation)
- Python: `client.record_external_event()` + static `FiGuardClient.verify_webhook()`
- TypeScript: `client.recordExternalEvent()` + static `FiGuardClient.verifyWebhook()`

### Per-chain spend cap
- `chain_root_event_id` + `max_subtree_quantity` on spend events
- Subtree cap enforced at authorization time across a full causal chain

### SDKs
- Python SDK (`figuard` on PyPI) — `FiGuardClient` with LangChain, CrewAI, OpenAI Agents, OpenAI function calling, Anthropic tool use integrations
- TypeScript SDK (`figuard` on npm) — `FiGuardClient` with full type coverage
- MCP server (`figuard-mcp` on npm) — 14 tools for Claude Code, Cursor, Claude Desktop, Windsurf
- Java SDK (`io.figuard:figuard-java-sdk:1.0.0`)

### Infrastructure
- Pre-built Docker image: `ghcr.io/figuard/figuard-core:latest` (GitHub Container Registry)
- Zero-dependency self-hosting: `docker compose -f docker-compose.prod.yml up`
- Swagger UI at `/swagger-ui.html` with API key auth, grouped endpoints, full descriptions
- OpenAPI spec at `/v3/api-docs`
- Prometheus metrics at `/actuator/prometheus` including `figuard.*` domain counters
- Flyway migrations (V1–V20)
- GitHub Actions CI: Python SDK (180 tests), TypeScript SDK (29 tests), MCP server (34 tests), Java (381 tests)
- Hosted sandbox: `https://figuard-sandbox-g1ha.onrender.com` with shared key `sb_live_demo`

### Security
- API keys stored as SHA-256 hashes, never persisted in plaintext
- Session tokens hashed before storage, never logged
- Webhook secrets encrypted at rest (AES-256-GCM, `WEBHOOK_SECRET_KEY` env var)
- Tenant isolation enforced at service layer on every query
