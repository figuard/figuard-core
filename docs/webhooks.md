# Webhooks

FiGuard sends real-time events to your server when budget and spend state changes. Use webhooks to trigger orchestration logic, update your UI, or alert on-call without polling the API.

---

## Event types

| Event | When it fires |
|---|---|
| `BUDGET_50_PCT` | Budget reaches 50% usage |
| `BUDGET_90_PCT` | Budget reaches 90% usage |
| `BUDGET_EXHAUSTED` | Available quantity reaches zero |
| `BUDGET_PAUSED` | Budget paused (anomaly detection or manually) |
| `BUDGET_EXPIRING_SOON` | Budget will expire within 60 minutes |
| `BUDGET_EXPIRED_UNUSED` | Budget expired with no spend |
| `BUDGET_RESUMED` | Paused budget was manually resumed |
| `ALLOCATION_EXHAUSTED` | A category allocation hit zero |
| `SPEND_CONFIRMED` | Event moved AUTHORIZED → CONFIRMED |
| `SPEND_DENIED` | An authorize call was denied |
| `SPEND_VOIDED` | An authorized event was voided |
| `SPEND_TREE_VOIDED` | An entire causal subtree was atomically voided |
| `SPEND_PAYMENT_FAILED` | Event moved AUTHORIZED → FAILED |
| `ANOMALY_DETECTED` | Spend exceeded the anomaly threshold |
| `VELOCITY_LIMIT_EXCEEDED` | Rolling-window rate limit was hit |
| `DELEGATION_TOKEN_REVOKED` | A delegation token was explicitly revoked |
| `LEDGER_INTEGRITY_VIOLATION` | Balance invariant breach detected |
| `ENTITLEMENT_STATE_CHANGED` | Entitlement state changed (e.g. NORMAL → APPROACHING) |
| `ENTITLEMENT_LIMIT_REACHED` | Entitlement hard limit hit |
| `ENTITLEMENT_RENEWED` | Entitlement balance reset at renewal |
| `WEBHOOK_TEST` | Test ping from `testWebhook()` |

---

## Quickstart

### 1. Register a webhook

Pick the events you care about and provide a secret — at least 16 characters — that you control. Store it immediately: the API never returns it again.

```python
# Python
from figuard import FiGuardClient

client = FiGuardClient()

webhook = client.create_webhook(
    url="https://yourapp.example.com/webhooks/figuard",
    secret="whsec_my_very_secret_key_1234",
    events=["BUDGET_EXHAUSTED", "BUDGET_90_PCT", "SPEND_CONFIRMED", "ANOMALY_DETECTED"],
)
print(webhook.id)   # save this — you'll need it to delete or inspect the webhook
```

```typescript
// TypeScript
import { FiGuardClient } from "figuard";

const client = new FiGuardClient();

const webhook = await client.createWebhook(
  "https://yourapp.example.com/webhooks/figuard",
  "whsec_my_very_secret_key_1234",
  ["BUDGET_EXHAUSTED", "BUDGET_90_PCT", "SPEND_CONFIRMED", "ANOMALY_DETECTED"],
);
console.log(webhook.id);
```

### 2. Receive and verify events

FiGuard signs every delivery with `X-Webhook-Signature: sha256=<hmac_hex>`. **Always verify before processing.**

```python
# Python — Flask example
from flask import Flask, request
from figuard import FiGuardClient, FiGuardWebhookVerificationError

app = Flask(__name__)
WEBHOOK_SECRET = "whsec_my_very_secret_key_1234"

@app.post("/webhooks/figuard")
def handle_figuard():
    payload = request.get_data()
    sig = request.headers.get("X-Webhook-Signature", "")

    try:
        event = FiGuardClient.verify_webhook(payload, sig, WEBHOOK_SECRET)
    except FiGuardWebhookVerificationError:
        return {"error": "invalid signature"}, 400

    event_type = event["eventType"]

    if event_type == "BUDGET_EXHAUSTED":
        budget_id = event["budgetId"]
        notify_ops(f"Budget {budget_id} exhausted — agent sessions will be denied")

    elif event_type == "ANOMALY_DETECTED":
        # Budget was auto-paused; review and resume after investigation
        budget_id = event["budgetId"]
        alert_oncall(f"Anomaly detected on budget {budget_id}")

    elif event_type == "SPEND_CONFIRMED":
        record_confirmed_payment(event["spendEventId"], event["confirmedQuantity"])

    return "", 204
```

```typescript
// TypeScript — Express example
import express from "express";
import { FiGuardClient, FiGuardWebhookVerificationError } from "figuard";

const app = express();
// Use raw body middleware so we can verify the HMAC
app.use("/webhooks/figuard", express.raw({ type: "application/json" }));

const WEBHOOK_SECRET = process.env.FIGUARD_WEBHOOK_SECRET!;

app.post("/webhooks/figuard", (req, res) => {
  let event: Record<string, unknown>;
  try {
    event = FiGuardClient.verifyWebhook(
      req.body as Buffer,
      req.headers["x-webhook-signature"] as string,
      WEBHOOK_SECRET,
    );
  } catch (err) {
    if (err instanceof FiGuardWebhookVerificationError) {
      return res.status(400).json({ error: "invalid signature" });
    }
    throw err;
  }

  switch (event["eventType"]) {
    case "BUDGET_EXHAUSTED":
      notifyOps(`Budget ${event["budgetId"]} exhausted`);
      break;
    case "ANOMALY_DETECTED":
      alertOncall(`Anomaly on budget ${event["budgetId"]}`);
      break;
    case "SPEND_CONFIRMED":
      recordPayment(event["spendEventId"] as string, event["confirmedQuantity"] as number);
      break;
  }

  res.status(204).send();
});
```

> **Raw body required.** The HMAC is computed over the exact bytes received. Any JSON re-serialization will break verification. Use `express.raw()` or equivalent — do not parse with `express.json()` before calling `verifyWebhook`.

### 3. Test connectivity

```python
result = client.test_webhook(webhook.id)
print(result.success)          # True if your endpoint returned 2xx
print(result.response_status)  # HTTP status code your endpoint returned
```

```typescript
const result = await client.testWebhook(webhook.id);
console.log(result.success);         // true if endpoint returned 2xx
console.log(result.responseStatus);  // HTTP status your endpoint returned
```

---

## Managing webhooks

```python
# List all registered webhooks
webhooks = client.list_webhooks()
for wh in webhooks:
    print(wh.id, wh.url, wh.events)

# Delete a webhook
client.delete_webhook(webhook_id="abc-123")
```

```typescript
const webhooks = await client.listWebhooks();
for (const wh of webhooks) {
  console.log(wh.id, wh.url, wh.events);
}

await client.deleteWebhook("abc-123");
```

---

## Delivery history and retries

FiGuard retries failed deliveries automatically (3 attempts, exponential backoff: 1s → 2s → 4s). You can also inspect and retry manually:

```python
# Deliveries for a specific webhook
deliveries = client.get_webhook_deliveries(webhook_id="abc-123")
for d in deliveries:
    print(d.status, d.event_type, d.response_status)

# All deliveries for the tenant — filter by status, event type, or time window
failed = client.get_all_deliveries(status="FAILED")
recent = client.get_all_deliveries(event_type="SPEND_CONFIRMED", since="2025-05-01T00:00:00Z")

# Manually retry a failed delivery
client.retry_delivery(delivery_id=failed[0].id)
```

```typescript
const deliveries = await client.getWebhookDeliveries("abc-123");
for (const d of deliveries) {
  console.log(d.status, d.eventType, d.responseStatus);
}

const failed = await client.getAllDeliveries({ status: "FAILED" });
const recent = await client.getAllDeliveries({
  eventType: "SPEND_CONFIRMED",
  since: "2025-05-01T00:00:00Z",
});

await client.retryDelivery(failed[0].id);
```

---

## Async Python

```python
from figuard import AsyncFiGuardClient

async with AsyncFiGuardClient() as client:
    webhook = await client.create_webhook(
        url="https://yourapp.example.com/webhooks/figuard",
        secret="whsec_my_very_secret_key_1234",
        events=["BUDGET_EXHAUSTED", "ANOMALY_DETECTED"],
    )
    deliveries = await client.get_all_deliveries(status="FAILED")
    for d in deliveries:
        await client.retry_delivery(d.id)
```

> `verify_webhook` is a static method on `FiGuardClient` — use it from both sync and async contexts. It has no network dependency; it just computes and compares an HMAC locally.

---

## Event payload structure

Every event payload includes:

```json
{
  "eventType": "BUDGET_EXHAUSTED",
  "budgetId": "bdg_...",
  "tenantId": "ten_...",
  "userId": "user_123",
  "currency": "USD",
  "unit": null,
  "timestamp": "2025-05-28T10:00:00Z"
}
```

Most events include additional fields. Key ones:

**`SPEND_CONFIRMED`**
```json
{
  "spendEventId": "evt_...",
  "requestedQuantity": 150.00,
  "confirmedQuantity": 142.50,
  "agentId": "travel_agent",
  "category": "flights",
  "totalLimit": 500.00,
  "quantitySpent": 142.50,
  "availableQuantity": 357.50
}
```
`category` is the event's `claimedCategory` (null if none was set) — use it for per-category cost attribution.

**`BUDGET_EXHAUSTED` / `BUDGET_90_PCT`**
```json
{
  "totalLimit": 500.00,
  "quantitySpent": 500.00,
  "quantityReserved": 0.00,
  "availableQuantity": 0.00,
  "percentUsed": 100.0
}
```

**`ANOMALY_DETECTED`**
```json
{
  "spendEventId": "evt_...",
  "requestedQuantity": 850.00,
  "baselineMean": 120.00,
  "threshold": 360.00,
  "agentId": "finance_agent"
}
```

**`SPEND_DENIED`**
```json
{
  "spendEventId": "evt_...",
  "requestedQuantity": 300.00,
  "denialReason": "BUDGET_EXHAUSTED",
  "denialMessage": "$0.00 remaining, $300.00 requested",
  "agentId": "travel_agent",
  "category": "flights"
}
```

---

## Security checklist

- **Verify every request.** Check `X-Webhook-Signature` before reading the payload.
- **Return 2xx quickly.** FiGuard marks a delivery as failed if your endpoint doesn't respond within the timeout. Do heavy processing asynchronously (queue it, return 204 immediately).
- **Use HTTPS.** Webhook payloads contain budget and spend details. Plain HTTP endpoints will be rejected.
- **Rotate secrets periodically.** Delete the old webhook config and create a new one with a fresh secret.
- **Idempotency.** FiGuard may retry on network errors — use `spendEventId` or `budgetId` as an idempotency key in your handler.
