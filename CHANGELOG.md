# Changelog

All notable changes to FiGuard are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

## [Unreleased] — pre-OSS (targeting 2026-06-03)

### Zero-config developer experience
- `FiGuardClient()` now requires no arguments — resolves: explicit params → `FIGUARD_API_KEY`/`FIGUARD_BASE_URL` env vars → shared public sandbox (`sb_live_demo`)
- One-time sandbox warning printed to stdout on fallback; suppressable via `FIGUARD_SUPPRESS_SANDBOX_WARNING=1`
- TypeScript SDK: equivalent zero-config constructor on `FiGuardClient`
- `auto_guard_langchain(executor, budget=500)` — one-liner: creates client + 24h budget + wires `FiGuardCallbackHandler` onto an `AgentExecutor`
- `auto_guard_crewai(tool, budget=500)` — one-liner: creates client + 24h budget + wraps tool's `_run` via `FiGuardCrewGuard`

### External events + webhook verification (V24)
- `POST /api/v1/events/external` — record a spend that already happened outside FiGuard
- Python: `client.record_external_event()` + static `FiGuardClient.verify_webhook()`
- TypeScript: `client.recordExternalEvent()` + static `FiGuardClient.verifyWebhook()`
- V24 migration: `event_source` + `occurred_at` columns on spend_events

### Per-chain spend cap (V23)
- `chain_root_event_id` + `max_subtree_quantity` on spend_events
- Subtree cap enforced at authorization time across a causal chain

---

## [v1.0.0] — 2026-05-19 — Initial OSS Release

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
- Counterfactual analysis — test a hypothetical policy against real history (`POST /budgets/{id}/replay/counterfactual`)

### Webhooks
- 10-attempt exponential backoff retry sweep (ShedLock prevents duplicate runs)
- Manual retry endpoint (`POST /webhooks/deliveries/{id}/retry`)
- Webhook secrets encrypted at rest (AES-256-GCM)
- 19 event types including velocity, anomaly, delegation, and lifecycle events
- HMAC-SHA256 signatures on all deliveries

### Receipts
- Shareable public receipt URL per budget (`GET /budgets/{id}/receipt`)
- HTML receipt page — no auth required, 90-day TTL (`GET /receipts/{token}`)

### SDKs
- Python SDK (`figuard` on PyPI) — `FiGuardClient` with LangChain, CrewAI, OpenAI Agents, Anthropic integrations
- TypeScript SDK (`@figuard/sdk` on npm) — `FiGuardClient` with full type coverage
- MCP server (`@figuard/mcp`) — 13 tools for Claude and other MCP-compatible agents
- Java SDK (`io.figuard:figuard-java-sdk`)

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
