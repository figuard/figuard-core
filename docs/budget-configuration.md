# Budget Configuration Reference

Every FiGuard budget has four layers. You only need Layer 1 to get started. Add the others as your use case requires.

---

## Layer 1 — What you're measuring

**Required. Pick one.**

| Situation | Field | Example |
|---|---|---|
| Real money | `currency` | `currency="USD"` |
| LLM tokens | `unit` | `unit="tokens"` |
| API calls | `unit` | `unit="api_calls"` |
| GPU hours | `unit` | `unit="gpu_hours"` |
| Any custom unit | `unit` | `unit="credits"` |

Set `currency` for monetary budgets. Set `unit` for everything else. Do not set both on the same budget.

```python
# Monetary budget
budget = client.create_budget(
    total_limit=500,
    currency="USD",
    expires_in="24h",
)

# Resource budget
budget = client.create_budget(
    total_limit=100_000,
    unit="tokens",
    expires_in="1h",
)
```

---

## Layer 2 — How strict the category rules are

**Optional. Only relevant if you add allocations. Pick one mode per allocation.**

| Mode | What it does | Use when |
|---|---|---|
| `OPEN` | Agent can claim any category string | You want tracking but no category enforcement |
| `CATEGORY_CONSTRAINED` | Agent must claim a category in `allowed_categories` | You want to prevent wrong-category spend |
| `STRICT` | Same as above + blocks forbidden item types | You want to prevent specific item types (gift cards, store credit) |

```python
# CATEGORY_CONSTRAINED — agent must claim "flight" or "hotel"
budget = client.create_budget(
    total_limit=500,
    currency="USD",
    expires_in="24h",
    allocations=[
        {
            "category": "flight",
            "limit": 300,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["flight"],
        },
        {
            "category": "hotel",
            "limit": 200,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["hotel"],
        },
    ],
)

# STRICT — additionally blocks specific item types
budget = client.create_budget(
    total_limit=500,
    currency="USD",
    expires_in="24h",
    allocations=[
        {
            "category": "flight",
            "limit": 300,
            "enforcement_mode": "STRICT",
            "allowed_categories": ["flight"],
            "forbidden_item_types": ["gift_card", "store_credit"],
        },
    ],
)
```

**Allocation sum rules:**
- Allocation limits can sum to less than `total_limit`. The remainder is an unallocated free pool — agents can spend from it without declaring a `claimed_category`.
- Allocation limits cannot exceed `total_limit`. Returns 400.

---

## Layer 3 — Intent and identity

**Optional. Helps anomaly detection and audit readability.**

| Field | What it does | Example |
|---|---|---|
| `intent_context` | Describes what this budget is for | `"travel booking for user_123"` |
| `external_reference` | Your own ID for this budget | `"order-2026-001"` |

```python
budget = client.create_budget(
    total_limit=500,
    currency="USD",
    expires_in="24h",
    intent_context="Q2 procurement run",
    external_reference="procurement-batch-q2-2026",
)
```

`external_reference` enables idempotent recreation. If your orchestrator crashes and restarts, calling `create_budget` again with the same `external_reference` returns the existing active budget instead of creating a duplicate. If the payload differs (different `total_limit`, different allocations), it returns 409 — this is intentional, it prevents silent misconfiguration on restart.

---

## Layer 4 — Safety controls

**Optional. Add what your use case needs.**

| Field | Default | What it does |
|---|---|---|
| `authorization_expiry_seconds` | `300` | Releases stuck reservations if the agent crashes before calling `confirm` or `fail` |
| `anomaly_detection_enabled` | `false` | Flags statistically unusual requests |
| `auto_pause_on_anomaly` | `false` | Pauses the budget automatically on anomaly detection (requires `anomaly_detection_enabled=True`) |
| `entity_dedup_enabled` | `false` | Blocks the same `entity_id` from being authorized twice — prevents duplicate payments |
| `max_transaction_quantity` | none | Hard ceiling on any single authorization amount |

```python
budget = client.create_budget(
    total_limit=10_000,
    currency="USD",
    expires_in="24h",
    authorization_expiry_seconds=300,    # release if agent crashes
    anomaly_detection_enabled=True,      # flag outliers
    auto_pause_on_anomaly=False,         # advisory only, don't auto-lock
    entity_dedup_enabled=True,           # prevent duplicate invoice payments
    max_transaction_quantity=2_000,      # no single transaction over $2k
)
```

**`authorization_expiry_seconds` guidance:**
Set this on every budget. The default is 300 seconds (5 minutes). If your agent typically calls `confirm` within seconds, you can tighten it. If it calls an external API that can take minutes, leave it at 300 or increase it. An expired reservation is released automatically — the budget is not affected, the agent just needs to re-authorize if it still wants to proceed.

**`entity_dedup_enabled` guidance:**
Use this when each real-world entity (invoice ID, booking reference, order number) should only be charged once, even if the agent retries. Pass the entity's ID as `entity_id` on the `authorize` call. If an authorization for that entity ID already exists and is AUTHORIZED or CONFIRMED, the second call returns `ENTITY_ALREADY_AUTHORIZED` with a pointer to the original event.

---

## Complete example

```python
budget = client.create_budget(
    # Layer 1 — what you're measuring (required, pick one)
    total_limit=500,
    currency="USD",            # monetary
    # OR: unit="tokens"        # resource

    # Layer 2 — category rules (optional)
    allocations=[
        {
            "category": "flight",
            "limit": 300,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["flight"],
        },
        {
            "category": "hotel",
            "limit": 200,
            "enforcement_mode": "STRICT",
            "allowed_categories": ["hotel"],
            "forbidden_item_types": ["gift_card"],
        },
    ],

    # Layer 3 — intent and identity (optional but recommended)
    intent_context="travel booking session",
    external_reference="trip-2026-001",    # idempotent recreation on restart

    # Layer 4 — safety controls (optional)
    expires_in="24h",
    authorization_expiry_seconds=300,      # always set this
    anomaly_detection_enabled=True,
    auto_pause_on_anomaly=False,
    entity_dedup_enabled=True,
    max_transaction_quantity=500,
)
```

---

## What happens when a budget expires

An expired budget stops accepting authorizations — any new `authorize` call returns `BUDGET_EXPIRED`. Existing confirmed events are unaffected. To keep a long-running session alive, call `POST /budgets/{id}/extend` before expiry. It can be called repeatedly; each call adds up to 24 hours from the current time.

---

## Related

- [Enforcement Features](enforcement.md) — full list of denial codes and how each one is triggered
- [Fleet Agents & Delegation Tokens](fleet-agents.md) — splitting a parent budget across sub-agents
- [Replay & Audit](replay.md) — reconstructing what happened on a budget after the fact
