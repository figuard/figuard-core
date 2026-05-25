# FiGuard API Reference

All endpoints are prefixed with your base URL (e.g. `https://api.figuard.io`).

## Authentication

Every request (except receipt display) requires an API key in the `X-Agent-Budget-Key` header:

```
X-Agent-Budget-Key: fg_live_...
```

The `/api/v1/authorize` endpoint additionally requires the budget session token in a separate header:

```
X-Session-Token: st_...
```

Session tokens are returned **once** when a budget is created. Store them securely — they cannot be retrieved again.

---

## Enumerations

| Type | Values |
|---|---|
| `BudgetStatus` | `ACTIVE` `PAUSED` `EXHAUSTED` `EXPIRED` `CANCELLED` |
| `SpendDecision` | `AUTHORIZED` `DENIED` `CONFIRMED` `FAILED` `VOIDED` |
| `DenialCode` | `INSUFFICIENT_FUNDS` `BUDGET_EXHAUSTED` `BUDGET_PAUSED` `BUDGET_EXPIRED` `BUDGET_CANCELLED` `CURRENCY_MISMATCH` `NO_MATCHING_ALLOCATION` `ALLOCATION_EXHAUSTED` `FORBIDDEN_ITEM_TYPE` `MISSING_CLAIMED_CATEGORY` `EXCEEDS_QUANTITY_LIMIT` `INTENT_SCOPE_VIOLATION` `ANOMALY_DETECTED` `VELOCITY_LIMIT_EXCEEDED` `ENTITY_ALREADY_AUTHORIZED` `DELEGATE_CAP_EXCEEDED` `DELEGATION_TOKEN_REVOKED` `DUPLICATE_REQUEST` `INVALID_PARENT_EVENT` `SUBTREE_CAP_EXCEEDED` |
| `EnforcementMode` | `OPEN` `CATEGORY_CONSTRAINED` `STRICT` |
| `FundingOperation` | `CREDIT` `DEBIT` `RESET` `RESET_SPENT` |
| `AllocationStatus` | `ACTIVE` `EXHAUSTED` |

---

## Budgets

### `POST /api/v1/budgets`

Create a spending envelope for an agent session.

**Request body**

```jsonc
{
  "userId": "user_123",              // required — your user/tenant identifier
  "totalLimit": 500.00,              // required — maximum spendable quantity
  "expiresAt": "2026-05-17T18:00:00Z", // required — ISO 8601, must be in the future

  // --- exactly one of currency OR unit is required ---
  "currency": "USD",                 // optional — 3-letter ISO code; makes this a monetary budget
  "unit": "tokens",                  // optional — free-form label; makes this a resource budget

  "externalReference": "trip-42",   // optional — your own reference ID (idempotency key for create)
  "softLimit": 450.00,               // optional — fires BUDGET_THRESHOLD_REACHED webhook at this value
  "maxTransactionQuantity": 200.00,  // optional — hard cap per individual authorize call
  "authorizationExpirySeconds": 1800, // optional — unreserves AUTHORIZED events after this many seconds

  // --- velocity controls (all optional) ---
  "velocityMaxPerMinute": 10,        // optional — max authorize attempts per rolling minute
  "velocityMaxAmountPerHour": 1000.00, // optional — max total requestedQuantity per rolling hour
  "velocityMaxPerDay": 50,           // optional — max authorize attempts per rolling 24h

  // --- anomaly detection (all optional) ---
  "anomalyDetectionEnabled": false,  // optional — default false
  "autoPauseOnAnomaly": true,        // optional — default true; only meaningful when detection enabled
  "anomalyPauseThresholdMultiplier": 3.0, // optional — flag if request > mean × multiplier
  "anomalyMinSampleSize": 5,         // optional — skip detection until this many events logged

  // --- intent scope (all optional) ---
  "intentContext": "book travel to NYC for Q2 offsite", // optional — stored for audit
  "intentTags": ["travel", "flight", "hotel"],          // optional — scope enforcement tags

  // --- allocations (optional — omit for a flat/unallocated budget) ---
  "allocations": [
    {
      "category": "flight",          // required
      "limit": 300.00,               // required
      "enforcementMode": "STRICT",   // optional — default CATEGORY_CONSTRAINED
      "allowedCategories": ["economy", "business"], // optional (required if CATEGORY_CONSTRAINED/STRICT)
      "forbiddenItemTypes": ["first_class"]         // optional
    },
    {
      "category": "hotel",
      "limit": 200.00
    }
  ],

  "entityDedupEnabled": false,       // optional — default false; dedup by entityId across calls
  "metadata": { "costCenter": "eng" } // optional — any JSON object, stored and returned as-is
}
```

**Response `201 Created`** (or `200 OK` if `externalReference` matched an existing budget)

```jsonc
{
  "id": "3fa85f64-...",
  "userId": "user_123",
  "externalReference": "trip-42",
  "tokens": [                        // ONLY present on create — store these, they are never returned again
    {
      "category": "default",         // "default" for flat budgets, allocation category for allocated ones
      "sessionToken": "st_live_...", // raw token — give this to the agent
      "sessionTokenPrefix": "st_live_AbC", // first 12 chars — safe to log
      "currency": "USD",             // null for resource budgets
      "unit": null                   // null for monetary budgets
    }
  ],
  "totalLimit": 500.00,
  "softLimit": 450.00,
  "maxTransactionQuantity": 200.00,
  "currency": "USD",
  "unit": null,
  "quantitySpent": 0.00,
  "quantityReserved": 0.00,
  "availableQuantity": 500.00,
  "authorizationExpirySeconds": 1800,
  "velocityMaxPerMinute": 10,
  "velocityMaxAmountPerHour": 1000.00,
  "velocityMaxPerDay": 50,
  "anomalyDetectionEnabled": false,
  "autoPauseOnAnomaly": true,
  "status": "ACTIVE",
  "intentContext": "book travel to NYC for Q2 offsite",
  "intentTags": ["travel", "flight", "hotel"],
  "allocations": [
    {
      "id": "a1b2c3...",
      "category": "flight",
      "limit": 300.00,
      "enforcementMode": "STRICT",
      "allowedCategories": ["economy", "business"],
      "forbiddenItemTypes": ["first_class"],
      "quantitySpent": 0.00,
      "quantityReserved": 0.00,
      "availableQuantity": 300.00,
      "status": "ACTIVE"
    }
  ],
  "expiresAt": "2026-05-17T18:00:00Z",
  "cancelledAt": null,
  "createdAt": "2026-05-16T10:00:00Z",
  "metadata": { "costCenter": "eng" },
  "traceId": "trc_..."
}
```

---

### `GET /api/v1/budgets`

List all budgets for your tenant.

**Query parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `page` | int | no | default `0` |
| `size` | int | no | default `20` |
| `status` | BudgetStatus | no | filter by status |
| `includeCancelled` | boolean | no | default `false` |
| `userId` | string | no | filter by userId |

**Response `200 OK`** — paginated `BudgetResponse` array (same shape as above, `tokens` field absent on list).

---

### `GET /api/v1/budgets/{id}`

Retrieve a single budget. `tokens` field is absent — session tokens are issued once at create.

---

### `PATCH /api/v1/budgets/{id}`

Update a budget. All fields are optional — only supplied fields are changed.

**Request body**

```jsonc
{
  "status": "PAUSED",                // optional — only PAUSED or ACTIVE accepted
  "totalLimit": 600.00,              // optional — increase or decrease the limit
  "expiresAt": "2026-05-20T18:00:00Z", // optional — must be in the future
  "velocityMaxPerMinute": 5,         // optional — set null to clear
  "velocityMaxAmountPerHour": 500.00, // optional
  "velocityMaxPerDay": 20            // optional
}
```

**Response `200 OK`** — full `BudgetResponse`.

---

### `POST /api/v1/budgets/{id}/cancel`

Cancel a budget immediately. Idempotent — cancelling an already-cancelled budget returns `200`.

No request body. **Response `200 OK`** — `BudgetResponse` with `status: "CANCELLED"`.

---

### `POST /api/v1/budgets/cancel-batch`

Cancel up to 100 budgets in one call. Idempotent.

**Request body** — array of budget UUIDs:
```json
["3fa85f64-...", "9b1deb4d-...", "..."]
```

**Response `200 OK`** — array of `BudgetResponse`.

---

### `POST /api/v1/budgets/{id}/resume`

Resume a `PAUSED` budget. Returns `409` if the budget is not currently paused.

**Request body**

```jsonc
{
  "overrideReason": "False positive — reviewed and cleared", // required
  "overrideBy": "sai@company.com"    // optional — who approved the resume
}
```

**Response `200 OK`** — `BudgetResponse` with `status: "ACTIVE"`.

---

### `POST /api/v1/budgets/{id}/extend`

Extend a budget's expiry. New expiry must be later than the current one and in the future.

**Request body**

```jsonc
{
  "expiresAt": "2026-05-25T18:00:00Z" // required
}
```

**Response `200 OK`** — `BudgetResponse`.

---

### `POST /api/v1/budgets/{id}/fund`

Adjust a budget's `totalLimit` or reset accounting fields.

**Request body**

```jsonc
{
  "operation": "CREDIT",   // required — CREDIT | DEBIT | RESET | RESET_SPENT
  "amount": 100.00,        // required — positive value
  "reason": "Q2 top-up"   // optional
}
```

| Operation | Effect |
|---|---|
| `CREDIT` | `totalLimit += amount` |
| `DEBIT` | `totalLimit -= amount` (errors if would go below spent+reserved) |
| `RESET` | `totalLimit = amount` |
| `RESET_SPENT` | `quantitySpent = 0`, `quantityReserved = 0` (amount field ignored) |

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "operation": "CREDIT",
  "amount": 100.00,
  "reason": "Q2 top-up",
  "previousTotalLimit": 500.00,
  "totalLimit": 600.00,
  "quantitySpent": 120.00,
  "quantityReserved": 50.00,
  "availableQuantity": 430.00,
  "status": "ACTIVE",
  "updatedAt": "2026-05-16T11:00:00Z",
  "traceId": "trc_..."
}
```

---

### `POST /api/v1/budgets/{id}/rotate-token`

Issue a new session token for a budget. The old token remains valid for 60 seconds (grace period), then stops working.

No request body. **Response `200 OK`**:
```json
{ "sessionToken": "st_live_newtoken..." }
```

---

### `GET /api/v1/budgets/{id}/ledger`

Paginated list of all spend events against this budget.

**Query parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `page` | int | no | default `0` |
| `size` | int | no | default `20` |
| `decision` | SpendDecision | no | filter by decision |
| `traceId` | string | no | filter by trace ID |

**Response `200 OK`** — paginated `SpendEventResponse` (see [Event fields](#event-fields)).

---

### `GET /api/v1/budgets/{id}/tree`

Returns the full spend event tree, grouped by parent-child relationships.

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "totalAuthorized": 450.00,
  "totalConfirmed": 320.00,
  "totalEvents": 5,
  "roots": [
    {
      "id": "evt_...",
      "decision": "CONFIRMED",
      "agentId": "travel-agent",
      "agentType": "langchain",
      "actionType": "PURCHASE",
      "description": "Flight SFO→JFK",
      "requestedQuantity": 350.00,
      "confirmedQuantity": 342.50,
      "currency": "USD",
      "claimedCategory": "flight",
      "claimedItemType": "economy",
      "intentContext": "...",
      "idempotencyKey": "...",
      "entityId": null,
      "denialReason": null,
      "parentEventId": null,
      "createdAt": "2026-05-16T10:05:00Z",
      "metadata": {},
      "children": []                 // nested SpendTreeNode array
    }
  ]
}
```

---

## Authorization

### `POST /api/v1/authorize`

**Headers:** `X-Agent-Budget-Key: fg_live_...` + `X-Session-Token: st_...`

Pre-flight check: reserves quantity and returns AUTHORIZED or DENIED before the real action executes. All checks are atomic — the reservation is held until confirmed, failed, or voided.

**Request body**

```jsonc
{
  "agentId": "travel-agent-1",      // required — identifies the agent instance
  "actionType": "PURCHASE",         // required — free-form action label
  "description": "Flight SFO→JFK", // required — human-readable, max 1000 chars
  "requestedQuantity": 350.00,      // required — must be >= 0
  "idempotencyKey": "uuid-v4-here", // required — same key = same response (safe to retry)

  "currency": "USD",                // optional — required for monetary budgets
  "claimedCategory": "flight",      // optional — required when budget has allocations
  "claimedItemType": "economy",     // optional — checked in STRICT enforcement mode
  "agentType": "langchain",         // optional — audit label
  "entityId": "booking-ref-99",     // optional — dedup across calls when entityDedupEnabled
  "parentEventId": "evt_...",       // optional — links to a parent event (causal chain)
  "maxSubtreeQuantity": 1000.00,    // optional — per-chain spend cap; only on root calls (no parentEventId)
  "intentContext": "business trip", // optional — stored for audit, not enforced
  "traceId": "trc_...",             // optional — correlates multiple related calls
  "dryRun": false,                  // optional — default false; skips all writes when true
  "metadata": {}                    // optional
}
```

**Response `200 OK`**

```jsonc
{
  "eventId": "evt_...",
  "decision": "AUTHORIZED",         // AUTHORIZED | DENIED
  "approvedQuantity": 350.00,       // present when AUTHORIZED
  "authorizedAt": "2026-05-16T10:05:00Z", // present when AUTHORIZED
  "denialReason": null,             // DenialCode — present when DENIED
  "denialMessage": null,            // human-readable explanation — present when DENIED
  "originalEventId": null,          // present when denial is ENTITY_ALREADY_AUTHORIZED
  "allocationSnapshot": null,       // present on allocation-specific denials
  "budgetSnapshot": {
    "id": "3fa85f64-...",
    "totalLimit": 500.00,
    "quantitySpent": 0.00,
    "quantityReserved": 350.00,
    "availableQuantity": 150.00,
    "status": "ACTIVE"
  },
  "traceId": "trc_..."
}
```

### Testing policies without side effects

Set `"dryRun": true` on any authorize call to run all enforcement checks without writing anything:

```bash
curl -X POST .../api/v1/authorize \
  -H "X-Agent-Budget-Key: fg_live_..." \
  -H "X-Session-Token: st_..." \
  -d '{
    "agentId": "test-agent",
    "actionType": "PURCHASE",
    "description": "Flight SFO→JFK",
    "requestedQuantity": 350.00,
    "idempotencyKey": "test-key-001",
    "dryRun": true
  }'
```

The response is identical to a live call — AUTHORIZED or DENIED with full reason codes and budget snapshot. But no SpendEvent is created, no reservation is held, and no webhook fires. The budget balance is unchanged.

Use this to:
- Test enforcement rules before going live
- Validate velocity limit thresholds
- Check category allocations without consuming budget
- Preview denial reasons during agent development

---

## Event Lifecycle

After an AUTHORIZED event, close the loop with one of: confirm (action succeeded), fail (action failed), or void (action was cancelled).

### Event fields

All lifecycle endpoints return `SpendEventResponse`:

```jsonc
{
  "id": "evt_...",
  "decision": "CONFIRMED",
  "agentId": "travel-agent-1",
  "agentType": "langchain",
  "actionType": "PURCHASE",
  "description": "Flight SFO→JFK",
  "requestedQuantity": 350.00,
  "confirmedQuantity": 342.50,      // set after confirm
  "currency": "USD",
  "entityId": "booking-ref-99",
  "claimedCategory": "flight",
  "claimedItemType": "economy",
  "intentContext": "business trip",
  "idempotencyKey": "uuid-v4-here",
  "denialReason": null,
  "failureReason": null,            // set after fail
  "parentEventId": null,
  "traceId": "trc_...",
  "createdAt": "2026-05-16T10:05:00Z",
  "metadata": {}
}
```

---

### `POST /api/v1/events/{id}/confirm`

Settle the charge. `confirmedQuantity` replaces the reserved amount — pass the actual quantity consumed (may be less than or equal to `requestedQuantity`).

**Request body**

```jsonc
{
  "confirmedQuantity": 342.50,           // required — actual quantity consumed, min 0.00
  "externalTransactionId": "stripe_ch_x" // optional — if set, void is blocked until a refund is recorded
}
```

---

### `POST /api/v1/events/{id}/fail`

Release the reservation. Use when the action failed after authorization (e.g. payment declined, API error).

**Request body**

```jsonc
{
  "reason": "PAYMENT_DECLINED"   // required
}
```

---

### `POST /api/v1/events/{id}/void`

Cancel the authorization. Use when the action was abandoned by the user or agent.

**Request body**

```jsonc
{
  "reason": "USER_CANCELLED",    // required
  "voidChildEvents": false       // optional — default false; if true, voids all child events too
}
```

---

### `POST /api/v1/events/{id}/void-tree`

Atomically void the target event and every `AUTHORIZED` descendant in its causal chain — in a single transaction.

Use this when an orchestration job is cancelled or fails mid-run and you want to release all child agent reservations at once. Without this, child events would stay as live reservations until they expire, freezing budget capacity.

`CONFIRMED` and already-`VOIDED` descendants are left untouched — only live `AUTHORIZED` reservations are released. If any descendant has an `externalTransactionId` (a committed payment), the entire operation fails — that event must be refunded before the tree can be voided.

**Request body**

```jsonc
{
  "reason": "ORCHESTRATION_JOB_CANCELLED"   // required
}
```

**Response `200 OK`**

```jsonc
{
  "rootEventId": "evt_abc123",
  "voidedCount": 4,                          // root + all voided descendants
  "totalQuantityReleased": 270.00,           // sum of requestedQuantity across all voided events
  "currency": "USD",                         // null for unit-based budgets
  "voidedEventIds": [
    "evt_abc123",                            // root first
    "evt_def456",                            // descendants in BFS order
    "evt_ghi789",
    "evt_jkl012"
  ],
  "reason": "ORCHESTRATION_JOB_CANCELLED"
}
```

**Error cases**

| HTTP | When |
|---|---|
| `404` | Root event not found or not owned by this tenant |
| `409` | Root event is not in `AUTHORIZED` state |
| `409` | `VOID_REQUIRES_REFUND` — root or a descendant has `externalTransactionId` set |

Fires a `SPEND_TREE_VOIDED` webhook with the same summary.

---

## Delegation Tokens

Fleet budgets can issue scoped sub-tokens to delegate a capped portion of the budget to a sub-agent.

### `POST /api/v1/budgets/{budgetId}/delegation-tokens`

**Request body**

```jsonc
{
  "label": "refund-agent-order-123",   // required — human identifier
  "caps": [                            // required — at least one cap
    {
      "category": "refund",            // required
      "limit": 50.00                   // required
    }
  ]
}
```

**Response `201 Created`** — `DelegationTokenResponse` with raw `sessionToken` (returned once only):

```jsonc
{
  "id": "dtk_...",
  "parentBudgetId": "3fa85f64-...",
  "label": "refund-agent-order-123",
  "status": "ACTIVE",
  "sessionToken": "st_live_delegated...", // only present on create
  "sessionTokenPrefix": "st_live_del",
  "caps": [
    {
      "id": "cap_...",
      "category": "refund",
      "totalLimit": 50.00,
      "quantitySpent": 0.00,
      "quantityReserved": 0.00,
      "availableQuantity": 50.00
    }
  ],
  "revokedAt": null,
  "createdAt": "2026-05-16T10:00:00Z"
}
```

---

### `GET /api/v1/budgets/{budgetId}/delegation-tokens`

List all delegation tokens for a fleet budget. `sessionToken` is absent on list responses.

---

### `GET /api/v1/delegation-tokens/{tokenId}`

Retrieve a single delegation token.

---

### `DELETE /api/v1/delegation-tokens/{tokenId}`

Revoke a delegation token immediately. Idempotent. **Response `200 OK`** — `DelegationTokenResponse` with `status: "REVOKED"`.

---

## Webhooks

### `POST /api/v1/webhooks`

Register a URL to receive event notifications.

**Request body**

```jsonc
{
  "url": "https://your-server.com/figuard-webhook", // required — must be HTTPS
  "secret": "at_least_16_chars_here",              // required — min 16 chars; used to sign payloads (encrypted at rest)
  "events": [                                       // required — one or more event types
    "BUDGET_50_PCT",
    "BUDGET_90_PCT",
    "BUDGET_EXHAUSTED",
    "BUDGET_PAUSED",
    "BUDGET_RESUMED",
    "BUDGET_EXPIRING_SOON",
    "BUDGET_EXPIRED_UNUSED",
    "ALLOCATION_EXHAUSTED",
    "SPEND_DENIED",
    "SPEND_CONFIRMED",
    "SPEND_VOIDED",
    "SPEND_TREE_VOIDED",
    "SPEND_PAYMENT_FAILED",
    "ANOMALY_DETECTED",
    "VELOCITY_LIMIT_EXCEEDED",
    "LEDGER_INTEGRITY_VIOLATION",
    "DELEGATION_TOKEN_REVOKED",
    "RENEWAL_TOKEN_DELIVERY_FAILED"
  ]
}
```

**Response `201 Created`** — secret is never returned:

```jsonc
{
  "id": "whk_...",
  "url": "https://your-server.com/figuard-webhook",
  "events": ["SPEND_AUTHORIZED", "SPEND_DENIED"],
  "active": true,
  "createdAt": "2026-05-16T10:00:00Z"
}
```

---

### `GET /api/v1/webhooks`

List all webhook configs for your tenant.

---

### `DELETE /api/v1/webhooks/{id}`

Delete a webhook config and all delivery history. **Response `204 No Content`**.

---

### `GET /api/v1/webhooks/{id}/deliveries`

Recent delivery attempts for a specific webhook config, newest first.

```jsonc
[
  {
    "id": "del_...",
    "webhookConfigId": "whk_...",   // null for direct-URL deliveries
    "targetUrl": null,              // populated for direct-URL deliveries (e.g. anomalyAlertWebhookUrl)
    "eventType": "SPEND_DENIED",
    "status": "DELIVERED",          // DELIVERED | FAILED | PENDING
    "responseStatus": 200,
    "responseBody": "ok",
    "payload": { "eventType": "SPEND_DENIED", "budgetId": "...", "..." },
    "attemptCount": 1,
    "deliveredAt": "2026-05-16T10:05:01Z",
    "createdAt": "2026-05-16T10:05:00Z"
  }
]
```

---

### `GET /api/v1/webhooks/deliveries`

All delivery attempts for your tenant across all webhook configs, newest first. Useful for the deliveries dashboard tab.

**Query parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `status` | `DELIVERED` \| `FAILED` \| `PENDING` | no | filter by delivery status |

Response shape is the same array as `GET /api/v1/webhooks/{id}/deliveries`.

---

### `GET /api/v1/webhooks/deliveries/failed-count`

Count of `FAILED` deliveries for your tenant. Used for the dashboard nav badge.

**Response `200 OK`**:
```json
{ "failedCount": 3 }
```

---

### `POST /api/v1/webhooks/deliveries/{deliveryId}/retry`

Manually retry a `FAILED` delivery. Fires asynchronously — returns `202 Accepted` immediately.

Returns `400` if the delivery is not in `FAILED` status. Returns `404` if the delivery does not belong to your tenant.

---

### `POST /api/v1/webhooks/{id}/test`

Fire a test payload to the configured URL synchronously. No delivery record is created. Always returns `200` regardless of whether your endpoint responded successfully.

```jsonc
{
  "success": true,
  "responseStatus": 200,
  "responseBody": "ok",
  "durationMs": 143,
  "errorMessage": null             // populated on network error
}
```

---

## API Keys

### `POST /api/v1/api-keys`

**Request body**

```jsonc
{
  "description": "production key" // optional — max 255 chars
}
```

**Response `201 Created`** — raw key returned once:

```jsonc
{
  "id": "key_...",
  "keyPrefix": "fg_live_AbC",
  "description": "production key",
  "active": true,
  "rawKey": "fg_live_...",        // only present on create and rotate — store this
  "createdAt": "2026-05-16T10:00:00Z",
  "lastUsedAt": null
}
```

---

### `GET /api/v1/api-keys`

List all keys. `rawKey` is never returned on list or get responses.

---

### `POST /api/v1/api-keys/{id}/revoke`

Revoke a key. Idempotent. **Response `200 OK`** — `ApiKeyResponse` with `active: false`.

---

### `POST /api/v1/api-keys/{id}/rotate`

Atomically revoke the current key and issue a replacement. `rawKey` present on response.

---

## Budget Replay

Reconstruct the exact state of a budget at any point in time.

### `GET /api/v1/budgets/{budgetId}/replay`

Full replay with per-event state snapshots.

**Query parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `from` | ISO 8601 datetime | no | start of replay window |
| `until` | ISO 8601 datetime | no | end of replay window |
| `includeDenied` | boolean | no | default `true` |
| `includeStateSnapshots` | boolean | no | default `true` |
| `pageSize` | int | no | default `100`, max `500` |
| `pageToken` | string | no | cursor from previous page |

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "replayWindow": {
    "from": "2026-05-16T00:00:00Z",
    "until": "2026-05-16T23:59:59Z",
    "durationSeconds": 86399
  },
  "summary": {
    "totalEvents": 12,
    "authorizedCount": 8,
    "deniedCount": 3,
    "confirmedCount": 6,
    "failedCount": 1,
    "voidedCount": 1,
    "uniqueAgents": 2,
    "peakReservedQuantity": 420.00,
    "peakReservedAt": "2026-05-16T14:22:00Z"
  },
  "initialState": { /* ReplayBudgetState — see below */ },
  "events": [
    {
      "eventIndex": 0,
      "event": {
        "eventId": "evt_...",
        "agentId": "travel-agent",
        "actionType": "PURCHASE",
        "description": "Flight SFO→JFK",
        "requestedQuantity": 350.00,
        "confirmedQuantity": null,
        "currency": "USD",
        "claimedCategory": "flight",
        "decision": "AUTHORIZED",
        "denialReason": null,
        "parentEventId": null,
        "delegatedTokenId": null,
        "createdAt": "2026-05-16T10:05:00Z",
        "confirmedAt": null,
        "millisSincePrevious": 0
      },
      "stateAfter": { /* ReplayBudgetState */ }
    }
  ],
  "finalState": { /* ReplayBudgetState */ },
  "nextPageToken": null
}
```

**`ReplayBudgetState` shape:**

```jsonc
{
  "snapshotAt": "2026-05-16T10:05:00Z",
  "eventIndex": 0,                  // -1 for initialState
  "triggeringEventId": "evt_...",
  "totalLimit": 500.00,
  "quantitySpent": 0.00,
  "quantityReserved": 350.00,
  "available": 150.00,
  "budgetStatus": "ACTIVE",
  "allocations": [
    {
      "category": "flight",
      "limit": 300.00,
      "quantitySpent": 0.00,
      "quantityReserved": 300.00,
      "available": 0.00,
      "enforcementMode": "STRICT"
    }
  ]
}
```

---

### `GET /api/v1/budgets/{budgetId}/replay/state`

Point-in-time state projection — what did the budget look like at exactly this moment?

**Query parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `at` | ISO 8601 datetime | **yes** | target timestamp |

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "projectedAt": "2026-05-16T12:00:00Z",
  "eventsApplied": 5,
  "state": { /* ReplayBudgetState */ }
}
```

---

### `GET /api/v1/budgets/{budgetId}/replay/timeline`

Lightweight chronological event list — no state snapshots.

**Query parameters:** `from`, `until` (both optional ISO 8601).

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "totalEvents": 12,
  "timeline": [
    {
      "eventIndex": 0,
      "eventId": "evt_...",
      "agentId": "travel-agent",
      "decision": "AUTHORIZED",
      "requestedQuantity": 350.00,
      "claimedCategory": "flight",
      "description": "Flight SFO→JFK",
      "createdAt": "2026-05-16T10:05:00Z",
      "millisSincePrevious": 0
    }
  ]
}
```

---

### `POST /api/v1/budgets/{budgetId}/replay/counterfactual`

Re-run a budget's history under a hypothetical policy. Shows exactly which authorizations would have changed.

**Request body**

```jsonc
{
  "hypotheticalPolicy": {            // provide this OR manifestVersion, not both
    "totalLimit": 400.00,            // optional
    "maxTransactionQuantity": 150.00, // optional
    "anomalyDetectionEnabled": true, // optional
    "allocations": [                 // optional — overrides the real allocation configuration
      {
        "category": "flight",        // required
        "limit": 200.00,             // required
        "enforcementMode": "STRICT", // optional
        "allowedCategories": ["economy"], // optional
        "forbiddenItemTypes": []     // optional
      }
    ]
  },
  "manifestVersion": "v2",           // optional — use a named policy manifest instead
  "from": "2026-05-16T00:00:00Z",   // optional — replay window start
  "until": "2026-05-16T23:59:59Z"  // optional — replay window end
}
```

**Response `200 OK`**

```jsonc
{
  "budgetId": "3fa85f64-...",
  "policySource": {
    "type": "inline",
    "manifestVersion": null
  },
  "actualPolicySummary": {
    "authorizedCount": 8,
    "deniedCount": 3,
    "totalQuantitySpent": 420.00
  },
  "hypotheticalPolicySummary": {
    "authorizedCount": 5,
    "deniedCount": 6,
    "totalQuantitySpent": 280.00,
    "additionalDenials": 3
  },
  "deltaEvents": [
    {
      "eventId": "evt_...",
      "actualDecision": "AUTHORIZED",
      "hypotheticalDecision": "DENIED",
      "hypotheticalDenialReason": "ALLOCATION_EXHAUSTED",
      "requestedQuantity": 180.00,
      "agentId": "travel-agent",
      "description": "Business class upgrade",
      "claimedCategory": "flight"
    }
  ]
}
```

---

## Subscriptions & Entitlements

Base path: `/api/v1/subscriptions`

All routes require `X-Agent-Budget-Key`.

### `GET /api/v1/subscriptions`
List all subscriptions for this tenant.

### `POST /api/v1/subscriptions`
Create a subscription. Returns 201.

```json
{
  "externalSubscriberId": "user_abc",
  "plan": "pro",
  "renewalPeriod": "MONTHLY",
  "startsAt": "2026-06-01T00:00:00Z"
}
```

### `GET /api/v1/subscriptions/{subscriptionId}`
Get a subscription by ID.

### `GET /api/v1/subscriptions/by-subscriber/{externalSubscriberId}`
Look up a subscription by your external subscriber ID (e.g. your user ID or customer ID).

### `POST /api/v1/subscriptions/{subscriptionId}/pause`
Pause a subscription. All linked budgets will receive HTTP 402 (`SUBSCRIPTION_PAUSED`) on the next authorize call.

### `POST /api/v1/subscriptions/{subscriptionId}/resume`
Resume a paused subscription. Authorize calls will succeed again (subject to entitlement limits).

### `POST /api/v1/subscriptions/{subscriptionId}/cancel`
Cancel a subscription. All linked budgets will receive HTTP 402 (`SUBSCRIPTION_CANCELLED`).

### `GET /api/v1/subscriptions/{subscriptionId}/entitlements`
List all entitlement items for this subscription.

### `POST /api/v1/subscriptions/{subscriptionId}/entitlements`
Add an entitlement item to a subscription. Returns 201.

```json
{
  "category": "api_calls",
  "periodLimit": 10000,
  "overagePolicy": "BLOCK",
  "warnAtPercentage": 80,
  "renewalPeriod": "MONTHLY"
}
```

`overagePolicy` values: `BLOCK` (deny spend when limit reached) | `WARN_ONLY` (allow spend, fire webhook).

### `GET /api/v1/subscriptions/{subscriptionId}/entitlements/{entitlementItemId}`
Get a single entitlement item, including `currentPeriodConsumed`, `currentPeriodReserved`, `state` (NORMAL / APPROACHING / LIMIT_REACHED), and `nextRenewalAt`.

### `POST /api/v1/subscriptions/{subscriptionId}/entitlements/{entitlementItemId}/reset`
Manually reset an entitlement item's consumed counter to zero and advance `nextRenewalAt`. Use for mid-period corrections or manual billing period control. Same logic as the auto-renewal sweep.

---

## Error Responses

All errors return a consistent shape:

```jsonc
{
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "requestedQuantity must be greater than or equal to 0",
  "traceId": "trc_..."
}
```

| HTTP | Error code | Common cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Missing required field, constraint violation |
| `400` | `CURRENCY_UNIT_CONFLICT` | Both `currency` and `unit` set on create |
| `401` | `UNAUTHORIZED` | Missing or invalid API key / session token |
| `403` | `TENANT_MISMATCH` | Session token belongs to a different tenant |
| `404` | `NOT_FOUND` | Budget, event, or token ID not found |
| `409` | `BUDGET_NOT_PAUSED` | Resume called on a non-paused budget |
| `409` | `INVALID_TRANSITION` | Lifecycle transition not allowed (e.g. confirm a VOIDED event) |
| `429` | `RATE_LIMITED` | Too many requests |
| `500` | `INTERNAL_ERROR` | Server error — safe to retry with backoff |
