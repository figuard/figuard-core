# FiGuard TypeScript SDK

[![npm version](https://img.shields.io/npm/v/figuard.svg)](https://www.npmjs.com/package/figuard)
[![Node](https://img.shields.io/node/v/figuard.svg)](https://www.npmjs.com/package/figuard)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/figuard/figuard-core/blob/main/LICENSE)

Pre-flight spend authorization for AI agents. Stop your agent from overspending before it happens.

## Install

```bash
npm install figuard
```

Requires Node.js 18+.

## Quickstart

```typescript
import { FiGuardClient, FiGuardDeniedException } from "figuard";

const client = new FiGuardClient({ apiKey: "fg_live_..." });

// 1. Create a budget for your user's session
const budget = await client.createBudget({
  userId: "user_123",
  totalLimit: 500,
  expiresIn: "24h",
  currency: "USD",
});

// 2. Pre-authorize every spend before it happens
try {
  const result = (await client.authorize({
    sessionToken: budget.tokens![0].sessionToken!,
    agentId: "agent_flight_booker",
    actionType: "PURCHASE",
    description: "NYC to LAX flight",
    requestedQuantity: 299,
    idempotencyKey: "txn-abc-001", // required — use a stable unique key
  })).raiseIfDenied();

  // 3. Execute the real transaction, then confirm
  await client.confirmEvent({
    eventId: result.eventId,
    confirmedQuantity: 299,
    externalTransactionId: externalTxId,
  });

} catch (e) {
  if (e instanceof FiGuardDeniedException) {
    console.log("Spend denied:", e.denialReason);
    // e.g. INSUFFICIENT_FUNDS, BUDGET_PAUSED, ANOMALY_DETECTED
  }
}
```

## Allocation-based budgets

Allocations ring-fence spend by category and enforce item-type rules:

```typescript
const budget = await client.createBudget({
  userId: "user_123",
  totalLimit: 500,
  expiresIn: "24h",
  currency: "USD",
  allocations: [
    {
      category: "flights",
      allowedCategories: ["flight", "airline"],
      limit: 300,
      enforcementMode: "STRICT",
      forbiddenItemTypes: ["gift_card", "upgrade"],
    },
    {
      category: "hotels",
      allowedCategories: ["hotel", "accommodation"],
      limit: 200,
      enforcementMode: "CATEGORY_CONSTRAINED",
    },
  ],
});

// claimedCategory must match one of allowedCategories
const result = await client.authorize({
  sessionToken: budget.tokens![0].sessionToken!,
  agentId: "travel_agent",
  actionType: "PURCHASE",
  description: "Flight to NYC",
  requestedQuantity: 250,
  idempotencyKey: "flight-nyc-001",
  claimedCategory: "flight",
  claimedItemType: "economy_ticket",
});
```

## Payment lifecycle

```typescript
// Authorize reserves funds — money has not moved yet
const result = (await client.authorize({ ... })).raiseIfDenied();

// Confirm when payment succeeds — finalizes the spend
await client.confirmEvent({ eventId: result.eventId, confirmedQuantity: 249 });

// Fail when the payment processor declines — releases the reservation
await client.failEvent({ eventId: result.eventId, reason: "PAYMENT_DECLINED" });

// Void if the action is cancelled before payment
await client.voidEvent({ eventId: result.eventId, reason: "USER_CANCELLED" });
```

## Anomaly detection

Enable per-budget anomaly detection to auto-pause budgets when a request is statistically unusual:

```typescript
const budget = await client.createBudget({
  userId: "user_123",
  totalLimit: 2000,
  expiresIn: "24h",
  currency: "USD",
  anomalyDetectionEnabled: true,
});
```

When a request exceeds `mean × multiplier` (default 3×) and at least 5 prior transactions exist, the budget is auto-paused and an `ANOMALY_DETECTED` webhook fires. Resume after review:

```typescript
const budget = await client.resumeBudget({
  budgetId,
  overrideReason: "Reviewed — legitimate bulk purchase",
  overrideBy: "ops-team",
});
```

## Error handling

```typescript
import {
  FiGuardDeniedException,  // decision === DENIED (not an HTTP error)
  FiGuardApiError,         // 4xx / 5xx from the API
  FiGuardConnectionError,  // network failure after all retries
} from "figuard";

try {
  const result = (await client.authorize({ ... })).raiseIfDenied();
} catch (e) {
  if (e instanceof FiGuardDeniedException) {
    console.log(e.denialReason);    // e.g. "INSUFFICIENT_FUNDS"
    console.log(e.denialMessage);   // human-readable explanation
    // if denialReason === "ENTITY_ALREADY_AUTHORIZED":
    //   e.originalEventId          // UUID of the existing event
  } else if (e instanceof FiGuardApiError) {
    console.log(e.statusCode, e.message);
  } else if (e instanceof FiGuardConnectionError) {
    console.log("Network failure:", e.message);
  }
}
```

The SDK automatically retries 5xx responses up to 3 times with exponential backoff (1s, 2s, 4s). 4xx errors are never retried.

## Ledger and reporting

```typescript
// Paginated spend history
const page = await client.getLedger({
  budgetId,
  page: 0,
  size: 20,
  decision: "CONFIRMED",
});
for (const event of page.events) {
  console.log(event.id, event.decision, event.confirmedQuantity);
}

// Causal spend tree (which agent triggered which spend)
const tree = await client.getSpendTree(budgetId);
for (const root of tree.roots) {
  console.log(root.event.agentId, root.children.length, "child events");
}
```

## Multi-resource authorization (CompositeGuard)

Authorize across multiple budgets atomically — if any resource denies, all prior authorizations in the same call are voided automatically:

```typescript
import { CompositeGuard, GuardedResource } from "figuard";

const guard = new CompositeGuard([
  new GuardedResource(client, tokenBudget.sessionToken!, "tokens"),
  new GuardedResource(client, usdBudget.sessionToken!, "USD"),
]);

const result = await guard.authorize({
  agentId: "travel_agent",
  actionType: "LLM_CALL",
  description: "search flights",
  requested: { tokens: 1500, USD: 0.09 },
  idempotencyKey: crypto.randomUUID(),
});

if (result.allAuthorized) {
  // ... do the work ...
  await guard.confirm(result, { tokens: 1423, USD: 0.085 });
} else {
  console.log(`Denied on ${result.firstDenialResource}: ${result.firstDenial?.denialReason}`);
}
```

## dry_run mode

Test your integration without writing to the ledger or firing webhooks:

```typescript
const result = await client.authorize({
  sessionToken: budget.tokens![0].sessionToken!,
  agentId: "agent_1",
  actionType: "PURCHASE",
  description: "Test authorization",
  requestedQuantity: 100,
  idempotencyKey: "test-key-001",
  dryRun: true, // nothing written, no webhooks fired
});
console.log(result.isAuthorized); // true/false based on real enforcement
```

## Configuration

```typescript
const client = new FiGuardClient({
  apiKey: "fg_live_...",
  baseUrl: "https://api.figuard.io", // override for self-hosted deployments
  timeoutMs: 30_000,                 // per-request timeout (default: 30s)
});
```

## Security notes

- The raw `sessionToken` is returned **once** on `createBudget()` and never again. Store it securely — treat it like a password.
- `idempotencyKey` is **required** on every `authorize()` call. Use a stable unique key per logical spend intent so retries are safe and never double-spend.

## Self-hosting

Point `baseUrl` at your own FiGuard deployment:

```typescript
const client = new FiGuardClient({
  apiKey: "fg_live_...",
  baseUrl: "http://localhost:8080",
});
```

Run FiGuard locally: see [figuard-core](https://github.com/figuard/figuard-core) for Docker setup.

## MCP Server

Use FiGuard directly from Claude Code, Cursor, or Claude Desktop — no SDK code required:

```bash
npx figuard-mcp
```

See [figuard-mcp](https://www.npmjs.com/package/figuard-mcp) for setup instructions.
