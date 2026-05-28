# Common Pitfalls

These are the three mistakes that catch most new FiGuard integrations. Each one is silent — no exception is thrown, the code appears to work, but the budget behaves unexpectedly.

---

## 1. Not calling `confirm_event` after a successful action

**What happens:** If you call `authorize()` but never call `confirm_event()`, `fail_event()`, or `void_event()`, the reservation stays in AUTHORIZED state indefinitely. The quantity appears as `quantity_reserved` on the budget — not spent, not released. The budget looks exhausted to the next agent even though the money hasn't actually moved.

```python
# ❌ Leaked reservation — $150 stays reserved forever
result = client.authorize(session_token=token, requested_quantity=150, ...)
if result.is_authorized:
    charge_card(150)
    # forgot confirm_event — $150 is stuck in reserved state

# ✅ Correct
result = client.authorize(session_token=token, requested_quantity=150, ...)
if result.is_authorized:
    actual = charge_card(150)
    client.confirm_event(event_id=result.event_id, confirmed_quantity=actual.amount)
```

**How to detect it:** Check `budget.quantity_reserved`. If it's non-zero after your agent run completes, you have leaked reservations. The audit ledger will show events in AUTHORIZED state with no subsequent CONFIRMED, FAILED, or VOIDED event.

**Resolution:** Call `void_event` on any stuck AUTHORIZED events, or set `authorization_expiry_seconds` on the budget — FiGuard will automatically void reservations that exceed this timeout.

```python
# Set a safety timeout on the budget so leaked reservations auto-release
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
    authorization_expiry_seconds=300,  # void any reservation not confirmed within 5 min
)
```

---

## 2. Reusing the same idempotency key with a different amount

**What happens:** FiGuard deduplicates by `idempotency_key`. If you send the same key twice — even with a different `requested_quantity` — the second call returns the **original response** silently. No error, no warning. The second charge appears authorized at the wrong amount.

```python
# ❌ Silent dedup — second call returns original result for $100, not $200
result1 = client.authorize(session_token=token, requested_quantity=100,
                            idempotency_key="booking-abc")

result2 = client.authorize(session_token=token, requested_quantity=200,
                            idempotency_key="booking-abc")  # returns result1!
print(result2.approved_quantity)  # → 100.0, not 200.0
```

**When this bites you:** Retry logic that reuses the same key string for a legitimately different request — e.g. two separate bookings in the same session that happen to generate the same key, or a key derived from a non-unique field like a date.

```python
# ✅ Use a UUID or a key derived from the unique action parameters
import uuid

idempotency_key = f"booking-{destination}-{amount}-{uuid.uuid4()}"
# or: store the key before the first attempt and reuse it only on retries
```

**The idempotency guarantee is intentional for retries.** If your first request times out and you retry, passing the same key ensures you don't double-authorize. The key should be unique per logical action, not per network call.

---

## 3. Category name typo silently bypasses allocation limits

**What happens:** When you create a budget with allocations (e.g. `flights: $600, hotels: $300`) and then call `authorize()` with a `claimed_category` that doesn't match any allocation name exactly, FiGuard falls back to OPEN enforcement — no category cap is applied. The authorization succeeds without checking against any limit.

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=1000.00,
    currency="USD",
    expires_in="24h",
    allocations=[
        {"category": "flights", "limit": 600.00},
        {"category": "hotels",  "limit": 300.00},
    ],
)

# ❌ Typo — "flight" doesn't match "flights" — OPEN enforcement, no cap
result = client.authorize(
    session_token=budget.primary_token.session_token,
    claimed_category="flight",   # ← should be "flights"
    requested_quantity=900.00,
)
print(result.is_authorized)  # → True — no allocation matched, no cap enforced!

# ✅ Exact match required
result = client.authorize(
    session_token=budget.primary_token.session_token,
    claimed_category="flights",  # matches allocation exactly
    requested_quantity=900.00,
)
print(result.is_authorized)  # → False — ALLOCATION_EXHAUSTED (over $600 limit)
```

**How to enforce strict category matching:** Set `enforcement_mode: "STRICT"` on the allocation. In STRICT mode, a `claimed_category` that doesn't match any allocation returns `MISSING_CLAIMED_CATEGORY` (DENIED) instead of falling through to OPEN enforcement.

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=1000.00,
    currency="USD",
    expires_in="24h",
    allocations=[
        {"category": "flights", "limit": 600.00, "enforcement_mode": "STRICT"},
        {"category": "hotels",  "limit": 300.00, "enforcement_mode": "STRICT"},
    ],
)
# Now a typo → DENIED with MISSING_CLAIMED_CATEGORY instead of silent pass-through
```

---

## Quick checklist

Before shipping a FiGuard integration to production:

- [ ] Every successful `authorize()` call is followed by `confirm_event()` on success or `fail_event()` / `void_event()` on failure
- [ ] Idempotency keys are unique per logical action (use UUID or action-derived keys)
- [ ] Category names in `authorize()` calls exactly match the names in your budget allocations — consider using constants
- [ ] `authorization_expiry_seconds` is set on the budget as a safety net for leaked reservations
- [ ] `enforcement_mode: "STRICT"` on allocations if you want category mismatches to hard-fail rather than pass through
