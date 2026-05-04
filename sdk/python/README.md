# FiGuard Python SDK

Pre-flight spend authorization for AI agents. Stop your agent from overspending before it happens.

## Install

```bash
pip install figuard

# Async support (LangChain, CrewAI, OpenAI Agents):
pip install figuard[async]
```

Requires Python 3.11+.

## Quickstart

```python
from figuard import FiGuardClient, FiGuardDeniedException

client = FiGuardClient(api_key="ab_live_...")

# 1. Create a budget for your user's session
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    expires_at="2024-12-31T23:59:59Z",
)

# 2. Pre-authorize every spend before it happens
try:
    result = client.authorize(
        session_token=budget.session_token,
        agent_id="agent_flight_booker",
        action_type="PURCHASE",
        description="NYC to LAX flight",
        requested_amount=299.00,
        idempotency_key="txn-abc-001",  # required — use a stable unique key
    ).raise_if_denied()

    # 3. Execute the real transaction, then confirm
    external_tx_id = payment_processor.charge(299.00)
    client.confirm_event(result.event_id, confirmed_amount=299.00,
                         external_transaction_id=external_tx_id)

except FiGuardDeniedException as e:
    print(f"Spend denied: {e.denial_reason}")
    # e.g. INSUFFICIENT_FUNDS, BUDGET_PAUSED, ANOMALY_DETECTED
```

## Async (LangChain / CrewAI / OpenAI Agents)

```python
import asyncio
from figuard import AsyncFiGuardClient

async def run_agent():
    async with AsyncFiGuardClient(api_key="ab_live_...") as client:
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            expires_at="2024-12-31T23:59:59Z",
        )

        result = await client.authorize(
            session_token=budget.session_token,
            agent_id="langchain_agent",
            action_type="PURCHASE",
            description="Hotel booking",
            requested_amount=189.00,
            idempotency_key="hotel-booking-001",
        )

        if result.is_authorized:
            await client.confirm_event(result.event_id, confirmed_amount=189.00)
```

## Allocation-based budgets

Allocations let you ring-fence spend by category and enforce item-type rules:

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=500.00,
    expires_at="2024-12-31T23:59:59Z",
    allocations=[
        {
            "category": "flights",
            "allowedCategories": ["flight", "airline"],
            "limit": 300.00,
            "enforcementMode": "STRICT",
            "forbiddenItemTypes": ["gift_card", "upgrade"],
        },
        {
            "category": "hotels",
            "allowedCategories": ["hotel", "accommodation"],
            "limit": 200.00,
            "enforcementMode": "CATEGORY_CONSTRAINED",
        },
    ],
)

# claimedCategory must match one of allowedCategories
result = client.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Flight to NYC",
    requested_amount=250.00,
    idempotency_key="flight-nyc-001",
    claimed_category="flight",
    claimed_item_type="economy_ticket",
)
```

## Payment lifecycle

```python
# Authorize reserves funds — money has not moved yet
result = client.authorize(...).raise_if_denied()

# Confirm when payment succeeds — finalizes the spend
client.confirm_event(result.event_id, confirmed_amount=249.00)

# Fail when the payment processor declines — releases the reservation
client.fail_event(result.event_id, reason="PAYMENT_DECLINED")

# Void if the action is cancelled before payment
client.void_event(result.event_id, reason="USER_CANCELLED")
```

## Anomaly detection

Enable per-budget anomaly detection to auto-pause budgets when a single request is statistically unusual:

```python
budget = client.create_budget(
    user_id="user_123",
    total_limit=2000.00,
    expires_at="2024-12-31T23:59:59Z",
    anomaly_detection_enabled=True,
    # optional: dedicated URL for anomaly alerts
    # anomaly_alert_webhook_url="https://your-service.com/alerts",
)
```

When a request exceeds `mean × multiplier` (default 3×) and at least 5 prior transactions exist, the budget is auto-paused and an `ANOMALY_DETECTED` webhook fires. Resume after review:

```python
budget = client.resume_budget(
    budget_id,
    override_reason="Reviewed — legitimate bulk purchase",
    override_by="ops-team",
)
```

## Error handling

```python
from figuard import (
    FiGuardDeniedException,   # decision == DENIED (not an HTTP error)
    FiGuardApiError,          # 4xx / 5xx from the API
    FiGuardConnectionError,   # network failure after all retries
)

try:
    result = client.authorize(...).raise_if_denied()
except FiGuardDeniedException as e:
    print(e.denial_reason)    # e.g. "INSUFFICIENT_FUNDS"
    print(e.denial_message)   # human-readable explanation
    # if denial_reason == "ENTITY_ALREADY_AUTHORIZED":
    #   e.original_event_id   # UUID of the existing event
except FiGuardApiError as e:
    print(e.status_code, e.message)
except FiGuardConnectionError as e:
    print("Network failure:", e)
```

The SDK automatically retries 5xx responses up to 3 times with exponential backoff (1s, 2s, 4s). 4xx errors are never retried.

## Ledger and reporting

```python
# Paginated spend history
page = client.get_ledger(budget_id, page=0, size=20, decision="CONFIRMED")
for event in page.events:
    print(event.id, event.decision, event.confirmed_amount)

# Causal spend tree (which agent triggered which spend)
tree = client.get_spend_tree(budget_id)
for root in tree.roots:
    print(root.event.agent_id, len(root.children), "child events")
```

## Configuration

```python
client = FiGuardClient(
    api_key="ab_live_...",
    base_url="https://api.figuard.io",  # override for self-hosted
    timeout=30,                          # per-request timeout in seconds
)
```

## Security notes

- The raw `session_token` is returned **once** on `create_budget()` and never again. Store it securely — treat it like a password.
- The SDK logs only the first 8 characters of the session token. The full token never appears in logs.
- `idempotency_key` is **required** on every `authorize()` call. Use a stable unique key per logical spend intent so retries are safe.
