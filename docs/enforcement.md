# Enforcement Features

Every `authorize()` call either succeeds or returns a structured denial. The denial code is machine-readable — your agent can branch on it.

---

## Denial codes

| Code | Meaning | When it fires |
|---|---|---|
| `INSUFFICIENT_FUNDS` | Requested quantity exceeds available budget | Budget has less remaining than `requested_quantity` |
| `ALLOCATION_EXHAUSTED` | Category allocation is fully reserved | The `claimed_category` allocation has no remaining capacity |
| `NO_MATCHING_ALLOCATION` | No allocation for the declared category | Budget has allocations but none match `claimed_category` |
| `MISSING_CLAIMED_CATEGORY` | Category required but not provided | Budget uses `CATEGORY_CONSTRAINED` or `STRICT` mode and `claimed_category` was omitted |
| `BUDGET_EXPIRED` | Budget has passed its `expiresAt` | Current time > `expiresAt` |
| `BUDGET_PAUSED` | Budget was paused by anomaly detection | `auto_pause_on_anomaly=True` and a previous request triggered the anomaly threshold |
| `BUDGET_CANCELLED` | Budget was explicitly cancelled | Orchestrator or developer called `POST /budgets/{id}/cancel` |
| `ANOMALY_DETECTED` | Spend pattern outside normal range | `anomaly_detection_enabled=True` and request exceeds the computed threshold |
| `EXCEEDS_QUANTITY_LIMIT` | Single transaction over the per-transaction cap | `max_transaction_quantity` is set and `requested_quantity` exceeds it |
| `ENTITY_DEDUP_REJECTED` | Duplicate entity within the dedup window | `entity_dedup_enabled=True` and the same `agent_id`+`idempotency_key` combination was already seen |
| `CURRENCY_MISMATCH` | Currency in request doesn't match budget | `currency` field on authorize call differs from budget's `currency` |
| `INTENT_SCOPE_VIOLATION` | Action outside declared intent | Request `action_type` not in the permitted scope for this budget |
| `DELEGATE_CAP_EXCEEDED` | Sub-agent over its delegation cap | Sub-agent's delegation token allocation is exhausted |
| `BUDGET_EXHAUSTED` | Budget limit fully consumed | `quantitySpent + quantityReserved == totalLimit` |
| `VELOCITY_LIMIT_EXCEEDED` | Too many authorize attempts within the rolling window | `velocity_max_per_minute`, `velocity_max_amount_per_hour`, or `velocity_max_per_day` is set and the rolling window count/amount has been reached |

---

## Allocation enforcement modes

Set per allocation in `create_budget(allocations=[...])`.

| Mode | Behaviour |
|---|---|
| `OPEN` | Allocation is advisory. Spend is tracked per category but not blocked when exhausted. |
| `CATEGORY_CONSTRAINED` | Spend from this allocation is blocked when it's exhausted. Other allocations are unaffected. |
| `STRICT` | Same as `CATEGORY_CONSTRAINED` plus: any `claimed_category` not listed in `allowed_categories` is denied with `NO_MATCHING_ALLOCATION`. |

---

## Anomaly detection

Enabled with `anomaly_detection_enabled=True` on `create_budget`. FiGuard computes a rolling baseline from confirmed transactions on the budget and flags requests that are statistically large relative to that baseline.

Two modes depending on `auto_pause_on_anomaly`:

```python
# Advisory — deny the anomalous request but keep the budget running
budget = client.create_budget(
    ...
    anomaly_detection_enabled=True,
    auto_pause_on_anomaly=False,   # default
)
# Anomalous request → DENIED with code ANOMALY_DETECTED
# Budget stays ACTIVE
# ANOMALY_DETECTED webhook fires

# Auto-pause — deny the request AND pause the budget
budget = client.create_budget(
    ...
    anomaly_detection_enabled=True,
    auto_pause_on_anomaly=True,
)
# Anomalous request → DENIED with code ANOMALY_DETECTED
# Budget moves to PAUSED — all subsequent authorizations fail with BUDGET_PAUSED
# BUDGET_PAUSED webhook fires, then ANOMALY_DETECTED webhook fires
```

To resume a paused budget:

```bash
curl -X POST http://localhost:8080/api/v1/budgets/{id}/resume \
  -H "X-Agent-Budget-Key: $KEY" \
  -d '{"overrideReason": "reviewed and cleared"}'
```

---

## Entity dedup

Enabled with `entity_dedup_enabled=True`. Prevents the same agent from authorizing the same logical action twice within the dedup window (default: 5 minutes). The dedup key is derived from `agent_id` + `idempotency_key`.

This is distinct from request-level idempotency (which replays the same decision when the same `idempotency_key` is retried). Entity dedup specifically blocks a second agent trying to claim the same transaction that another agent already authorized.

---

## Webhooks

FiGuard fires webhooks asynchronously for the following event types. Configure via `POST /api/v1/webhooks`.

| Event type | Fires when |
|---|---|
| `SPEND_DENIED` | Any authorization is denied |
| `ANOMALY_DETECTED` | Anomaly detection triggers (both advisory and auto-pause) |
| `BUDGET_PAUSED` | Auto-pause fires on anomaly |
| `VELOCITY_LIMIT_EXCEEDED` | A velocity window limit is exceeded (fires once per window; subsequent violations are silent) |
| `BUDGET_50_PCT` | Budget crosses 50% utilization |
| `BUDGET_90_PCT` | Budget crosses 90% utilization |
| `BUDGET_EXHAUSTED` | Budget is fully consumed |

All payloads include `eventType`, `budgetId`, `totalLimit`, `availableQuantity`, `percentUsed`, and `timestamp`. Delivery is retried once on non-2xx. The `X-Webhook-Signature` header carries an HMAC-SHA256 signature for payload verification.

---

## Velocity controls

Velocity controls limit how many authorize attempts — or how much total quantity — an agent can request within a rolling time window. They complement the total budget limit by catching runaway loops early, before meaningful spend accumulates.

Set any combination of these optional fields on `create_budget`:

| Field | Type | Window | What it limits |
|---|---|---|---|
| `velocity_max_per_minute` | int | Rolling 60 seconds | Maximum number of authorize calls |
| `velocity_max_amount_per_hour` | decimal | Rolling 60 minutes | Maximum total `requested_quantity` |
| `velocity_max_per_day` | int | Rolling 24 hours | Maximum number of authorize calls |

**Counting semantics — ALL attempts count, not just approved ones.** A budget-exhausted agent that retries in a loop still increments the velocity counter on every call. This is intentional: velocity controls are designed to catch runaway retry loops, which typically fire even when the budget says `BUDGET_EXHAUSTED`.

**Dedup behavior.** The first authorize call that crosses a velocity limit writes a `VELOCITY_LIMIT_EXCEEDED` SpendEvent and fires the `VELOCITY_LIMIT_EXCEEDED` webhook. Subsequent violations within the same window are denied silently — no new ledger entry is written. This keeps the audit log readable without flooding it.

**Rolling windows (not fixed).** Each window is computed as a true rolling interval: last 60 seconds, last 60 minutes, or last 24 hours measured from the current timestamp. There is no fixed clock boundary (e.g. top of the hour) to game.

```python
# Advisory — deny the request but keep the budget running
budget = client.create_budget(
    ...
    velocity_max_per_minute=10,         # at most 10 calls per 60-second window
    velocity_max_amount_per_hour=500.0, # at most $500 requested per hour
    velocity_max_per_day=200,           # at most 200 calls per 24 hours
)
# 11th call in 60 seconds → DENIED with code VELOCITY_LIMIT_EXCEEDED
# VELOCITY_LIMIT_EXCEEDED webhook fires once
# 12th, 13th … calls in the same window → silently denied (no new ledger entry)

# Velocity limits can be updated without recreating the budget:
client.update_budget(budget.id, velocity_max_per_minute=20)
```

To update limits after creation, use `PATCH /budgets/{id}` with the updated field(s). The change takes effect immediately for the next window evaluation.

**V2 roadmap:** quiet hours (per-timezone suppression windows), per-agent tracking within a shared budget. See [Known Limitations](known-limitations.md) for current query strategy notes.
