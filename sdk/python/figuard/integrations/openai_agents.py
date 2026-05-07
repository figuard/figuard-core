"""
FiGuard integration for the OpenAI Agents SDK.

Wraps individual agent tool functions with FiGuard pre-flight authorization
via a decorator applied before the function is registered as a tool.

Installation::

    pip install figuard[openai-agents]

Quick start::

    from agents import Agent, Runner, function_tool
    from figuard import FiGuardClient
    from figuard.integrations.openai_agents import guarded_function_tool

    client = FiGuardClient(api_key="ab_live_demo")
    budget = client.create_budget(user_id="user_123", total_limit=500.00, ...)

    @function_tool
    @guarded_function_tool(
        client=client,
        session_token=budget.session_token,
        category="flight",
        amount_key="price",
    )
    def book_flight(destination: str, price: float) -> str:
        \"\"\"Book a flight to the specified destination.\"\"\"
        # ... real implementation ...
        return f"Flight to {destination} booked for ${price}"

    agent = Agent(name="Travel Agent", tools=[book_flight])
    result = await Runner.run(agent, "Book a flight to NYC for $299")

**Decorator order matters:**
Apply ``@guarded_function_tool(...)`` *before* ``@function_tool`` (i.e., as the
inner decorator). This lets FiGuard wrap the raw Python function — with access
to all kwargs — before the Agents SDK converts it to a tool schema.

**What happens on denial:**
The function returns a structured denial string. The agent receives it as the
tool result and can adjust its plan::

    "FiGuard DENIED: INSUFFICIENT_FUNDS — no remaining budget for flights"

**Amount extraction:**
Reads the keyword argument named ``amount_key`` (default ``"amount"``) directly
from the function's kwargs. Use ``amount_key`` to match whatever parameter
name your function uses for spend amount (e.g. ``"price"``, ``"cost"``).
"""

from __future__ import annotations

import logging
from functools import wraps
from typing import Any, Callable, Optional
from uuid import uuid4

try:
    import agents as _agents_sdk  # noqa: F401 — presence check only
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "FiGuard OpenAI Agents integration requires the openai-agents package. "
        "Install it with: pip install figuard[openai-agents]"
    ) from exc

from figuard.client import FiGuardClient

logger = logging.getLogger(__name__)


def guarded_function_tool(
    client: FiGuardClient,
    session_token: str,
    *,
    category: Optional[str] = None,
    amount_key: str = "amount",
    agent_id: str = "openai_agents_agent",
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """
    Decorator that wraps a tool function with FiGuard pre-flight authorization.

    Apply *before* ``@function_tool`` (as the inner decorator) so FiGuard wraps
    the raw Python function with full access to its arguments::

        @function_tool
        @guarded_function_tool(client=client, session_token=st, category="flight")
        def book_flight(destination: str, amount: float) -> str:
            ...

    On denial, the wrapped function returns a structured denial string rather
    than raising, so the agent can reason about the outcome. On tool error, the
    original exception is re-raised after notifying FiGuard.

    :param client:         FiGuardClient instance.
    :param session_token:  Budget session token for this agent run.
    :param category:       FiGuard claimed category. Required for allocation budgets.
    :param amount_key:     Name of the kwarg that holds the spend amount.
                           Defaults to ``"amount"``.
    :param agent_id:       Agent identifier written to the FiGuard audit ledger.
    """
    def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
        @wraps(fn)
        def wrapper(**kwargs: Any) -> Any:
            amount = float(kwargs.get(amount_key, 0.0))
            description = f"{fn.__name__}: {str(kwargs)[:200]}"

            auth = client.authorize(
                session_token=session_token,
                agent_id=agent_id,
                action_type="TOOL_CALL",
                description=description,
                requested_amount=amount,
                claimed_category=category,
                idempotency_key=str(uuid4()),
            )

            if not auth.is_authorized:
                msg = f"FiGuard DENIED: {auth.denial_reason}"
                if auth.denial_message:
                    msg += f" — {auth.denial_message}"
                logger.info(
                    "figuard: DENIED tool=%s reason=%s event_id=%s",
                    fn.__name__, auth.denial_reason, auth.event_id,
                )
                return msg

            try:
                result = fn(**kwargs)
            except Exception as exc:
                client.fail_event(
                    event_id=auth.event_id,
                    reason="TOOL_ERROR",
                    error_message=str(exc)[:500],
                )
                raise

            try:
                client.confirm_event(auth.event_id, confirmed_amount=amount)
                logger.debug(
                    "figuard: CONFIRMED tool=%s event_id=%s", fn.__name__, auth.event_id
                )
            except Exception as exc:
                logger.warning(
                    "figuard: confirm failed tool=%s event_id=%s: %s",
                    fn.__name__, auth.event_id, exc,
                )

            return result

        return wrapper
    return decorator
