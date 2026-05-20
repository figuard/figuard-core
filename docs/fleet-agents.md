# Fleet Agents & Delegation Tokens

A fleet budget lets an orchestrator carve up a shared pool into scoped sub-budgets for each sub-agent. Sub-agents never see the parent session token — they only get a delegation token with a specific cap.

---

## How it works

```
Orchestrator
  │
  ├── create_budget($10,000)  →  fleet session token
  │
  ├── create_delegation_token(cap=$3,000, category=refund)  →  refund token
  ├── create_delegation_token(cap=$5,000, category=compute) →  compute token
  │
  └── hands each token to the right sub-agent
        RefundAgent   uses refund token  → blocked at $3k regardless of fleet headroom
        ComputeAgent  uses compute token → blocked at $5k regardless of fleet headroom
```

Both caps count against the fleet total. The fleet is exhausted when the sum of all delegated spend reaches $10k — even if individual caps still have headroom.

---

## Creating a fleet budget

```python
fleet = client.create_budget(
    user_id="orchestrator",
    total_limit=10_000.00,
    currency="USD",
    expires_in="8h",
)
```

---

## Issuing delegation tokens

```python
# fleet.tokens is a list — one entry per dimension so agents have full context
# on all spending dimensions. For simple fleet budgets there is one entry with
# category="default". Use primary_token as a convenience accessor.
fleet_session_token = fleet.primary_token.session_token

refund_token = client.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet_session_token,   # orchestrator authenticates with fleet token
    label="refund-processor",
    caps=[{"category": "refund", "limit": 3_000.00}],
    expires_in="4h",
)

compute_token = client.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet_session_token,
    label="compute-runner",
    caps=[{"category": "compute", "limit": 5_000.00}],
    expires_in="4h",
)
```

Pass `refund_token.session_token` to the refund agent. Never share `fleet_session_token` beyond the orchestrator.

---

## Sub-agent authorize call

```python
# RefundProcessorAgent — uses its delegation token, not the fleet token
auth = client.authorize(
    session_token=refund_token.session_token,
    agent_id="refund_processor",
    action_type="REFUND",
    description="Order #8821 refund",
    requested_quantity=150.00,
    claimed_category="refund",
    idempotency_key="refund-8821",
)

if auth.is_authorized:
    payment_gateway.issue_refund(150.00)
    client.confirm_event(auth.event_id, confirmed_quantity=150.00)
```

---

## Enforcement

| Condition | Denial code |
|---|---|
| Sub-agent exceeds its cap | `DELEGATE_CAP_EXCEEDED` |
| Fleet total is exhausted | `BUDGET_EXHAUSTED` |
| Fleet is paused (anomaly) | `BUDGET_PAUSED` |
| Delegation token expired | `BUDGET_EXPIRED` |

A sub-agent is blocked at whichever limit it hits first — its own cap or the fleet total.

---

## Revoking a delegation token

```python
client.revoke_delegation_token(
    budget_id=fleet.id,
    token_id=refund_token.id,
    session_token=fleet_session_token,
)
# Subsequent authorize calls with refund_token.session_token → 401
```

Revocation is immediate. Any authorization already in AUTHORIZED state (reserved, not yet confirmed) is unaffected — the reservation still counts against the fleet total until confirmed, failed, or voided.

---

## Token expiry

Delegation tokens expire independently of the parent budget. If a delegation token expires before the parent budget, the sub-agent's authorizations start failing with `BUDGET_EXPIRED`. Issue a new delegation token to resume.

A delegation token cannot outlive its parent budget — `expires_in` is capped at the parent's `expiresAt`.

---

## Framework integrations and fleet

Framework integrations (LangChain, CrewAI, OpenAI Agents SDK) handle single-agent budgets automatically — create a budget, pass the session token to the handler or guard, done.

For fleet deployments with per-agent caps, use the Python or TypeScript SDK directly. This gives you full control over which delegation token each agent receives, which categories each agent can spend from, and when tokens are revoked. The examples in this document show exactly that pattern — create delegation tokens in the orchestrator, hand each sub-agent only its own scoped token, and let FiGuard enforce the caps automatically.
