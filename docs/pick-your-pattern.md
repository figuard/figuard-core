# Pick Your Pattern

Find your situation in the tree. Each leaf shows the budget creation call and the matching authorize call your agent makes at runtime.

→ For full parameter reference, see [Budget Configuration](budget-configuration.md).

---

## What are you controlling?

```
What are you controlling?
│
├── Money (USD, EUR, any currency)
│   ├── Single agent, one task
│   ├── Single agent, multiple spend categories
│   └── Multiple agents sharing one budget
│
├── Tokens / LLM calls
│   ├── Limit total tokens for a task
│   └── Limit tokens per category (inference vs embedding)
│
└── API calls / external service calls
    ├── Rate-limit an agent's external calls
    └── Limit by service type (email, SMS, webhook)
```

---

## Money

### Single agent, one task

No categories. Agent spends freely up to the limit.

```python
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)
```

At runtime, the agent authorizes each spend:

```python
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK roundtrip",
    requested_quantity=267.00,
    idempotency_key="booking-sfo-jfk-001",
)

if auth.is_authorized:
    stripe.charge(auth.approved_quantity)
    client.confirm_event(auth.event_id, confirmed_quantity=267.00)
else:
    log.warning("Blocked: %s", auth.denial_reason)
    # denial_reason is a machine-readable code: INSUFFICIENT_FUNDS,
    # BUDGET_EXPIRED, BUDGET_PAUSED, etc.
```

---

### Single agent, multiple spend categories

Use allocations to enforce per-category limits. The agent must declare which category it is spending from on every authorize call.

```python
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
    allocations=[
        {
            "category": "flight",
            "limit": 300.00,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["flight"],
        },
        {
            "category": "hotel",
            "limit": 200.00,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["hotel"],
        },
    ],
)
```

At runtime, the agent declares `claimed_category` on every authorize call. Without it, the request is denied with `MISSING_CLAIMED_CATEGORY`.

```python
# Flight spend
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK roundtrip",
    requested_quantity=267.00,
    claimed_category="flight",          # must match an allocation
    idempotency_key="booking-sfo-jfk-001",
)

# Hotel spend — uses the hotel allocation
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square 2 nights",
    requested_quantity=198.00,
    claimed_category="hotel",
    idempotency_key="hotel-marriott-001",
)
```

If the flight allocation is exhausted, the hotel allocation is unaffected. Each allocation enforces independently.

---

### Multiple agents sharing one budget (fleet)

A parent budget holds the total limit. Delegation tokens carve out sub-limits for each sub-agent. Sub-agents never see the parent session token — they only get their delegation token.

```python
# Orchestrator creates the fleet budget
fleet = client.create_budget(
    user_id="orchestrator",
    total_limit=10_000.00,
    currency="USD",
    expires_in="8h",
)

# Orchestrator issues a delegation token for each sub-agent
refund_token = client.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet.session_token,   # orchestrator authenticates
    label="refund-processor",
    caps=[{"category": "refund", "limit": 3_000.00}],
    expires_in="4h",
)

compute_token = client.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet.session_token,
    label="compute-runner",
    caps=[{"category": "compute", "limit": 5_000.00}],
    expires_in="4h",
)

# Hand each token to its sub-agent — never share the parent session_token
```

Each sub-agent authorizes using its own delegation token, not the fleet's session token:

```python
# RefundProcessorAgent — uses refund_token.session_token
auth = client.authorize(
    session_token=refund_token.session_token,   # delegation token, not fleet token
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

A sub-agent cannot exceed its cap (`DELEGATE_CAP_EXCEEDED`) even if the parent budget has headroom. The parent budget also enforces its own total — if the fleet hits $10k, all sub-agents are blocked regardless of their individual caps.

---

## Tokens / LLM calls

### Limit total tokens for a task

```python
budget = client.create_budget(
    user_id="research_agent",
    total_limit=100_000,
    unit="tokens",              # no currency — dimensionless resource budget
    expires_in="1h",
)
```

At runtime, the agent authorizes before each LLM call with an estimated token count, then confirms with the actual count from the API response:

```python
# Before the LLM call — authorize with an estimate
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="research_agent",
    action_type="LLM_CALL",
    description="Summarize search results",
    requested_quantity=4_000,           # estimated tokens
    idempotency_key="summary-step-003",
)

if auth.is_authorized:
    response = openai.chat.completions.create(...)
    actual_tokens = response.usage.total_tokens

    # Confirm with what was actually consumed
    client.confirm_event(auth.event_id, confirmed_quantity=actual_tokens)
else:
    raise BudgetExhaustedError(auth.denial_reason)
```

The reservation pattern (reserve estimate, confirm actual) is intentional. It prevents two concurrent LLM calls from both reading the same available balance and both getting approved when there's only enough for one.

---

### Limit tokens per category

```python
budget = client.create_budget(
    user_id="research_agent",
    total_limit=100_000,
    unit="tokens",
    expires_in="1h",
    allocations=[
        {
            "category": "inference",
            "limit": 80_000,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["inference"],
        },
        {
            "category": "embedding",
            "limit": 20_000,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["embedding"],
        },
    ],
)
```

At runtime, declare which category each call belongs to:

```python
# Inference call
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="research_agent",
    action_type="LLM_CALL",
    description="Generate report draft",
    requested_quantity=8_000,
    claimed_category="inference",
    idempotency_key="inference-step-007",
)

# Embedding call
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="research_agent",
    action_type="EMBEDDING",
    description="Embed document chunk",
    requested_quantity=512,
    claimed_category="embedding",
    idempotency_key="embed-chunk-042",
)
```

---

## API calls / external service calls

### Rate-limit an agent's external calls

```python
budget = client.create_budget(
    user_id="outreach_agent",
    total_limit=1_000,
    unit="api_calls",
    expires_in="24h",
)
```

At runtime, authorize before each external call. `requested_quantity=1` for a single call:

```python
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="outreach_agent",
    action_type="EXTERNAL_CALL",
    description="Send welcome email to user@example.com",
    requested_quantity=1,
    idempotency_key=f"email-{user_id}-welcome",
)

if auth.is_authorized:
    email_client.send(to=user_email, template="welcome")
    client.confirm_event(auth.event_id, confirmed_quantity=1)
```

---

### Limit by service type

```python
budget = client.create_budget(
    user_id="outreach_agent",
    total_limit=1_000,
    unit="api_calls",
    expires_in="24h",
    allocations=[
        {
            "category": "email",
            "limit": 100,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["email"],
        },
        {
            "category": "sms",
            "limit": 50,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["sms"],
        },
        {
            "category": "webhook",
            "limit": 850,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["webhook"],
        },
    ],
)
```

At runtime:

```python
# Email call
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="outreach_agent",
    action_type="EXTERNAL_CALL",
    description="Password reset email",
    requested_quantity=1,
    claimed_category="email",
    idempotency_key=f"email-reset-{user_id}",
)

# SMS call
auth = client.authorize(
    session_token=budget.session_token,
    agent_id="outreach_agent",
    action_type="EXTERNAL_CALL",
    description="2FA SMS to +1-555-0100",
    requested_quantity=1,
    claimed_category="sms",
    idempotency_key=f"sms-2fa-{user_id}-{timestamp}",
)
```

Once the email allocation hits 100, email calls are denied with `ALLOCATION_EXHAUSTED`. SMS and webhook allocations are unaffected.

---

## What changes between patterns

| Pattern | Budget creation | Authorize call |
|---|---|---|
| Single agent, no categories | `currency` or `unit`, no allocations | No `claimed_category` needed |
| Single agent, with categories | Add `allocations` | Must pass `claimed_category` |
| Fleet / multi-agent | Create delegation tokens per sub-agent | Sub-agent uses `delegation_token.session_token` |
| Resource (tokens, calls) | `unit="tokens"` instead of `currency` | `requested_quantity` is the resource unit, not money |

---

## Related

- [Budget Configuration](budget-configuration.md) — full parameter reference for all four layers
- [Fleet Agents & Delegation Tokens](fleet-agents.md) — delegation token caps, expiry, and revocation
- [Enforcement Features](enforcement.md) — full list of denial codes and what triggers each one
