# Known Limitations

---

## Authorization is cooperative, not cryptographic

FiGuard authorizes what the agent *declares*. If an agent says `claimed_category="flight"` and `requested_quantity=200`, FiGuard checks whether that fits within the budget's flight allocation. It cannot verify the agent is actually booking a flight or that the amount is accurate.

The threat model is: FiGuard stops agents that exceed their configured limits, catches anomalous spend patterns, and creates an auditable record. It does not stop a malicious agent that accurately declares its intent and stays within budget.

---

## No cross-budget aggregation

Each budget is enforced independently. FiGuard does not provide a view that says "agent X has spent $Y across all budgets this week." That query lives in your data warehouse.

---

## Webhook delivery is best-effort

FiGuard attempts delivery up to 10 times. The first 4 attempts happen immediately with short delays (0 s, 1 s, 2 s, 4 s). If all four fail, a background sweep retries with exponential backoff: 1 min, 2 min, 4 min, 8 min, 16 min, 32 min. After 10 total attempts the delivery is marked terminal.

You can manually trigger a retry at any time via `POST /api/v1/webhooks/deliveries/{id}/retry` or from the Webhooks tab in the dashboard.

Build your webhook handler to be idempotent — the same event can be delivered more than once if a network error occurs after your endpoint has processed the payload but before it returned 2xx. For critical use cases where webhook delivery cannot be missed, poll the ledger (`GET /api/v1/budgets/{id}/ledger`) as a fallback.

---

## Framework integrations don't support fleet delegation

LangChain, CrewAI, and OpenAI Agents integrations work with a single session token. They cannot route different tool calls to different delegation tokens. For per-agent spend isolation in a fleet, use the raw Python or TypeScript SDK and wire delegation tokens manually.

---

## RESET_SPENT does not zero quantityReserved

`RESET_SPENT` zeros `quantitySpent` and reactivates an exhausted budget. In-flight authorizations (status `AUTHORIZED`, not yet confirmed or voided) retain their reservations. The new available capacity is `totalLimit - quantityReserved`. This is intentional — reservations represent real work in progress.

---

## Tokens are returned once at budget creation

`budget.tokens` is a list of session tokens returned once at creation and never again. Each token in the list has a `category` and a `session_token` field. If you lose them, you cannot recover them — hashes are stored, raw tokens are not. Create a new budget or use `external_reference` for idempotent restart.

For simple budgets (no named allocations), there is one token with `category="default"`. Access it via `budget.primary_token.session_token`. For multi-dimension budgets, build a map: `tokens = {t.category: t.session_token for t in budget.tokens}`.

---

## Dashboard requires local access on self-hosted

The dashboard at `http://localhost:8080/ui` is served by the FiGuard container. It is not behind any authentication — do not expose port 8080/ui to the internet on a self-hosted instance. The API at port 8080 requires `X-Agent-Budget-Key` on all requests.

---

## No built-in multi-tenancy UI

Tenant isolation is enforced at the API level — each API key belongs to one tenant and can only see that tenant's budgets. There is no admin UI for managing tenants. Provisioning is done by creating API keys via `POST /api/v1/api-keys`.

---

## Authorizations per budget are serialized

FiGuard acquires a pessimistic write lock on the budget row for every `authorize` call. This serializes all concurrent agents sharing a budget, guaranteeing ACID correctness: no two agents can double-spend the same available quantity.

The throughput ceiling is approximately **1 / avg_db_transaction_time** per budget. On a typical Postgres instance with a local connection this is around 200–500 authorizations per second. It is not a per-server ceiling — different budgets lock independently, so horizontal scaling is straightforward: distribute agents across multiple budgets.

For high-concurrency fleet scenarios where many agents share a single envelope, the recommended pattern is a parent delegation model: one root budget issues delegation tokens to child agents, each child gets its own delegated budget with a hard cap. Agents authorize against their child budget (independent lock), not the shared root.

---

## Velocity controls add two queries per authorization

When velocity limits are configured (`velocity_max_per_minute`, `velocity_max_amount_per_hour`, `velocity_max_per_day`), each `authorize` call issues one `COUNT` query and one `SUM` query against `spend_events` for the relevant rolling windows. Both queries run inside the budget's pessimistic lock.

This is correct and efficient for most agent workloads (dozens to low hundreds of calls per minute per budget). At high concurrent load — roughly 500+ `authorize` calls per second on a single budget — these queries add measurable latency.

**V3 roadmap:** replace the live queries with a dedicated counter table incremented atomically per budget per window.  
**V4 roadmap:** Redis sliding-window counters using sorted sets, eliminating the DB round-trip for velocity checks entirely.

---

## No built-in API rate limiting

FiGuard does not include application-level rate limiting on the `authorize` endpoint or any other API endpoint. For production deployments, configure rate limiting in your reverse proxy (nginx, Caddy, Traefik). See [Self-Hosting](self-hosting.md) for configuration guidance.

---

## No database-level tenant isolation (Row Level Security)

Tenant isolation is currently enforced at the application layer: every repository query includes a `tenant_id` predicate, and every API request is scoped to a tenant via its API key. A bug in the application code could in principle issue a query that returns cross-tenant data.

PostgreSQL Row Level Security (RLS) would add a second enforcement layer at the database itself — policies that reject any query not scoped to the current tenant, regardless of what the application sends. This is the correct defense-in-depth for a multi-tenant SaaS deployment.

For self-hosted deployments (the primary use case for this project), each instance is typically single-tenant, making RLS less critical.

**V2 roadmap:** optional RLS policy migration for operators running FiGuard in a shared multi-tenant mode.
