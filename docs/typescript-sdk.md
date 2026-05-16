# TypeScript SDK

```bash
npm install figuard
```

All methods are async and return typed responses. The client is isomorphic — it works in Node.js and browser environments.

---

## Client setup

```typescript
import { FiGuardClient } from 'figuard';

const client = new FiGuardClient({
  apiKey: 'sb_live_demo',
  baseUrl: 'https://sandbox.figuard.io',  // omit for production
});
```

---

## Budget lifecycle

```typescript
// Create
const budget = await client.createBudget({
  userId: 'agent_001',
  totalLimit: 500.00,
  currency: 'USD',
  expiresIn: '24h',
  authorizationExpirySeconds: 300,
  intentContext: 'travel booking session',
});

// budget.tokens is an array — one entry per dimension so agents have full context
// on all spending dimensions for this user. For simple single-token budgets:
// one entry with category "default". Use tokens[0] or build a map for multi-token.
const sessionToken = budget.tokens![0].sessionToken!

// Authorize
const auth = await client.authorize({
  sessionToken,
  agentId: 'travel_agent',
  actionType: 'PURCHASE',
  description: 'JetBlue SFO→JFK roundtrip',
  requestedQuantity: 270.00,
  idempotencyKey: 'booking-001',
});

if (auth.isAuthorized) {
  await stripe.charges.create({ amount: auth.approvedQuantity * 100, currency: 'usd' });
  await client.confirmEvent(auth.eventId, { confirmedQuantity: 267.00 });
} else {
  console.warn('Blocked:', auth.denialReason);
}
```

---

## Fluent chaining

```typescript
// raiseIfDenied() throws FiGuardDeniedException on denial, returns auth on success
const sessionToken = budget.tokens![0].sessionToken!
const auth = await client
  .authorize({ sessionToken, ...params })
  .then(a => a.raiseIfDenied());
```

---

## Fleet / delegation tokens

```typescript
const fleetBudget = await client.createBudget({
  userId: 'orchestrator',
  totalLimit: 10_000,
  currency: 'USD',
  expiresIn: '8h',
});

// fleetBudget.tokens is an array — one entry per dimension. For simple fleet
// budgets use tokens[0]; for entitlement-backed budgets build a map by category.
const fleetSessionToken = fleetBudget.tokens![0].sessionToken!

const refundToken = await client.createDelegationToken({
  budgetId: fleetBudget.id,
  sessionToken: fleetSessionToken,
  label: 'refund-processor',
  caps: [{ category: 'refund', limit: 3_000 }],
  expiresIn: '4h',
});

// Sub-agent uses refundToken.sessionToken, never fleetSessionToken directly
const auth = await client.authorize({
  sessionToken: refundToken.sessionToken,
  agentId: 'refund_processor',
  actionType: 'REFUND',
  requestedQuantity: 150,
  claimedCategory: 'refund',
  idempotencyKey: 'refund-8821',
});
```

---

## Event lifecycle

```typescript
// Confirm — transaction succeeded, move reserved → spent
await client.confirmEvent(auth.eventId, { confirmedQuantity: 267.00 });

// Fail — transaction failed, release reservation
await client.failEvent(auth.eventId, { reason: 'PAYMENT_DECLINED' });

// Void — cancelled before execution, release reservation
await client.voidEvent(auth.eventId);
```

---

## Retry behaviour

The client automatically retries on 5xx responses and network errors with exponential backoff (3 attempts, 1s / 2s / 4s). 4xx responses are not retried.

---

## Error types

| Error | When |
|---|---|
| `FiGuardApiError` | 4xx/5xx response from the API |
| `FiGuardDeniedException` | `raiseIfDenied()` called on a denied authorization |
| `FiGuardConnectionError` | All retries exhausted (network unreachable) |

---

## MCP Server

If you're using Claude Code, Cursor, or Claude Desktop, use the MCP server instead of the SDK directly — see [MCP Server](mcp-server.md).
