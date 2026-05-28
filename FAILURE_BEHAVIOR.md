# FiGuard Failure Behavior

This document explains what happens when FiGuard's authorization server is unreachable and how to configure your application's failure mode.

---

## Default: fail closed

By default, `FiGuardClient` **fails closed** — if the server cannot be reached, `authorize()` throws a `FiGuardConnectionError`. Your agent pipeline surfaces the error and stops, exactly as it would if any other critical dependency were down.

```python
# Python (default)
client = FiGuardClient()
result = client.authorize(...)  # raises FiGuardConnectionError if server is down
```

```typescript
// TypeScript (default)
const client = new FiGuardClient();
const result = await client.authorize(...);  // throws FiGuardConnectionError if server is down
```

This is the right default for production workflows where an unauthorized spend is worse than a failed request.

---

## Opt in: fail open

If your use case tolerates running without spend controls (e.g. during an outage you'd rather agents keep working than block), you can opt in to **fail open**:

```python
# Python
client = FiGuardClient(fail_open=True)

result = client.authorize(
    session_token=token,
    agent_id="agent_flight_booker",
    action_type="PURCHASE",
    description="Book NYC–LAX",
    requested_quantity=299,
)

if result.is_authorized:
    if result.is_fallback:
        # FiGuard was unreachable — no ledger entry was created
        logger.warning("FiGuard offline; spend not tracked for event %s", result.event_id)
    execute_purchase(299)
    if not result.is_fallback:
        client.confirm_event(event_id=result.event_id, confirmed_quantity=299)
```

```typescript
// TypeScript
const client = new FiGuardClient({ failOpen: true });

const result = await client.authorize({ ... });

if (result.isAuthorized) {
  if (result.isFallback) {
    // FiGuard was unreachable — no ledger entry was created
    console.warn(`FiGuard offline; spend not tracked for event ${result.eventId}`);
  }
  await executePurchase(299);
  if (!result.isFallback) {
    await client.confirmEvent({ eventId: result.eventId, confirmedQuantity: 299 });
  }
}
```

### What `is_fallback` / `isFallback` means

- `is_fallback: True` — the server was unreachable; `authorize()` returned a synthetic AUTHORIZED result
- The `event_id` starts with `"fallback_"` — there is no corresponding ledger record
- `confirm_event` / `fail_event` / `void_event` with a fallback `event_id` are **silent no-ops** — they return successfully without making a network call
- A `WARNING`-level log line is emitted with the agent ID, action type, requested quantity, and the underlying connection error

---

## Choosing the right mode

| Scenario | Recommended mode |
|---|---|
| Financial transactions, payments, compliance-gated actions | **fail closed** (default) |
| Internal tooling where availability matters more than spend accuracy | **fail open** |
| Shadow / observation mode (spend tracking only) | **fail open** (enforcement is advisory anyway) |
| Unknown / haven't thought about it | **fail closed** (default) — safer to surface the error |

---

## Why FiGuard might be unreachable

1. **Network partition** — your service cannot reach `figuard-sandbox-g1ha.onrender.com` (or your self-hosted instance)
2. **Server overload** — FiGuard returns 5xx responses after retries are exhausted
3. **DNS failure** — hostname does not resolve
4. **Timeout** — the request exceeds `timeoutMs` (default: 30 seconds) after all retries

All four are surfaced as `FiGuardConnectionError` in Python and TypeScript.

---

## Timeout and retry configuration

FiGuard retries failed requests up to **3 times** with exponential backoff before giving up. The per-request timeout (before retries start) defaults to **30 seconds**.

```python
# Python: set timeout
client = FiGuardClient(timeout=10)  # 10 seconds per attempt
```

```typescript
// TypeScript: set timeout
const client = new FiGuardClient({ timeoutMs: 10_000 });  // 10 seconds per attempt
```

---

## Monitoring recommendations

Regardless of your failure mode, instrument for FiGuard connectivity:

- Log every `FiGuardConnectionError` (or `is_fallback=True`) with the agent ID and action type
- Alert if the fallback rate exceeds a threshold — it indicates FiGuard is unhealthy
- When `fail_open=True`, reconcile untracked spend after FiGuard recovers using `record_external_event()`

```python
# Record spend that happened during a FiGuard outage
client.record_external_event(
    session_token=token,
    agent_id="agent_flight_booker",
    action_type="PURCHASE",
    description="Book NYC–LAX (recorded post-outage)",
    quantity=299,
    occurred_at="2025-06-01T14:32:00Z",
    event_source="OUTAGE_RECONCILIATION",
)
```
