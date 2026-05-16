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

Webhooks are retried once on non-2xx. If the second attempt fails, the delivery is marked FAILED and not retried again. Build your webhook handler to be idempotent, and poll the ledger for critical use cases where webhook delivery cannot be missed.

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

The dashboard at `http://localhost:5173` is served by the FiGuard container. It is not behind any authentication — do not expose port 5173 to the internet on a self-hosted instance. The API at port 8080 requires `X-Agent-Budget-Key` on all requests.

---

## No built-in multi-tenancy UI

Tenant isolation is enforced at the API level — each API key belongs to one tenant and can only see that tenant's budgets. There is no admin UI for managing tenants. Provisioning is done by creating API keys via `POST /api/v1/api-keys`.

---

## Velocity window uses a live COUNT query

The rolling velocity windows (`velocity_max_per_minute`, `velocity_max_amount_per_hour`, `velocity_max_per_day`) are currently evaluated by issuing a live `COUNT` or `SUM` query against the `spend_events` table for the relevant window. This works well at moderate throughput.

At very high concurrency — roughly 1,000+ `authorize` calls per second on a single budget — this query can become a bottleneck. If you expect that volume, consider:

- **Option B (V3 roadmap):** a dedicated counter table incremented atomically per budget per window, avoiding the full `spend_events` scan.
- **Option C (V4 roadmap):** a Redis sliding-window counter using sorted sets, eliminating the database round-trip entirely.

For most agent workloads (dozens to low hundreds of calls per minute) the current implementation is sufficient.
