# figuard-langchain

Pre-flight spend authorization for LangChain agents. One line to add budget enforcement and velocity controls to any AgentExecutor.

```bash
pip install figuard-langchain
```

## Quick start

```python
from figuard_langchain import auto_guard_langchain

# Monetary budget — enforces dollar spend on tools with an "amount" parameter
executor = auto_guard_langchain(executor, budget=500)

# Velocity control — catches runaway loops even when tools have no dollar amount
executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)

result = executor.invoke({"input": "Book a flight to NYC"})
```

`auto_guard_langchain` creates a FiGuard budget, wires a callback handler to the executor, and returns the same executor ready to run. Uses the [shared public sandbox](https://figuard-sandbox-g1ha.onrender.com/ui) by default — no account required.

## What gets enforced

| Scenario | How to enforce |
|---|---|
| Tools with dollar amounts (`price`, `cost`, `amount` fields) | `budget=500` — denies when total spend exceeds $500 |
| Research agents, code agents, any tool without a financial parameter | `velocity_max_per_minute=10` — denies the 11th call in 60 seconds |
| Both | Pass both params — monetary ceiling + loop detection |

## OTEL / Langfuse integration

FiGuard emits authorization spans to any OpenTelemetry backend automatically when one is configured. With Langfuse:

```python
from langfuse import Langfuse
from figuard_langchain import auto_guard_langchain

lf = Langfuse(public_key="...", host="https://us.cloud.langfuse.com")
executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)
```

Every `authorize()` call appears as a `GUARDRAIL` span in the Langfuse trace. Denied calls appear as `ERROR` spans — visible in the execution graph without any extra configuration.

## Full control

For advanced fleet patterns, delegation tokens, or per-category allocations, use the main `figuard` package directly:

```bash
pip install figuard[langchain]
```

```python
from figuard import FiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler

client = FiGuardClient()
budget = client.create_budget(
    user_id="alice",
    total_limit=500.00,
    currency="USD",
    velocity_max_per_minute=10,
    allocations=[
        {"category": "flights", "limit": 300.00, "mode": "CATEGORY_CONSTRAINED"},
        {"category": "hotels",  "limit": 200.00, "mode": "CATEGORY_CONSTRAINED"},
    ],
)
handler = FiGuardCallbackHandler(
    client=client,
    session_token=budget.primary_token.session_token,
)
executor.callbacks = [handler]
```

## Self-hosting

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
docker compose up -d
```

Set `FIGUARD_BASE_URL=http://localhost:8080` and `FIGUARD_API_KEY=<your-key>` before running your agent.

## Links

- [GitHub](https://github.com/figuard/figuard-core)
- [Documentation](https://figuard.io/docs)
- [Public sandbox](https://figuard-sandbox-g1ha.onrender.com/ui)
- [PyPI (figuard)](https://pypi.org/project/figuard/)
