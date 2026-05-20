"""
FiGuard integration for Anthropic tool use.

Wraps individual tool functions with FiGuard pre-flight authorization.
Apply as a decorator on the function that you call when dispatching
Anthropic ``tool_use`` blocks — authorization happens transparently before
the function runs.

Installation::

    pip install figuard[anthropic]

Quick start::

    from anthropic import Anthropic
    from figuard import FiGuardClient
    from figuard.integrations.anthropic import guarded_anthropic_tool

    anthropic_client = Anthropic()
    figuard_client = FiGuardClient(api_key="fg_live_demo")
    budget = figuard_client.create_budget(user_id="user_123", total_limit=500.00, ...)

    @guarded_anthropic_tool(
        client=figuard_client,
        session_token=budget.primary_token.session_token,
        category="flight",
        amount_key="amount",
    )
    def book_flight(destination: str, amount: float) -> str:
        \"\"\"Book a flight to the given destination.\"\"\"
        # ... real implementation ...
        return f"Flight to {destination} booked for ${amount}"

    # Define tool schemas as normal
    tools = [{"name": "book_flight", "description": "...", "input_schema": {...}}]

    response = anthropic_client.messages.create(
        model="claude-opus-4-6",
        max_tokens=1024,
        tools=tools,
        messages=[{"role": "user", "content": "Book a flight to NYC for $299"}],
    )

    # Dispatch tool calls — FiGuard authorizes before each function runs
    for block in response.content:
        if block.type == "tool_use" and block.name == "book_flight":
            result = book_flight(**block.input)   # ← authorization happens here
            # Note: Anthropic passes block.input as a dict, not a JSON string

**What happens on denial:**
The function returns a structured denial string instead of executing. Return it
as the tool result in a ``tool_result`` block so the model can adjust its plan::

    "FiGuard DENIED: NO_MATCHING_ALLOCATION — flight is not in this budget"

**Amount extraction:**
Reads the keyword argument named ``amount_key`` (default ``"amount"``) from the
kwargs passed to the function. Anthropic passes ``block.input`` as a parsed dict,
so the amount is available directly without any JSON decoding.
"""

from __future__ import annotations

import logging
from functools import wraps
from typing import Any, Callable, Dict, Optional
from uuid import uuid4

try:
    import anthropic as _anthropic  # noqa: F401 — presence check only
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "FiGuard Anthropic integration requires the anthropic package. "
        "Install it with: pip install figuard[anthropic]"
    ) from exc

from figuard.client import FiGuardClient

logger = logging.getLogger(__name__)


def guarded_anthropic_tool(
    client: FiGuardClient,
    session_token: str,
    *,
    category: Optional[str] = None,
    amount_key: str = "amount",
    agent_id: str = "anthropic_agent",
    amount_extractor: Optional[Callable[..., float]] = None,
    debug: bool = False,
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """
    Decorator that wraps a tool function with FiGuard pre-flight authorization.

    Apply to the function you dispatch when handling Anthropic ``tool_use``
    blocks::

        @guarded_anthropic_tool(client=client, session_token=st, category="flight")
        def book_flight(destination: str, amount: float) -> str:
            ...

        # Later, in your dispatch loop:
        for block in response.content:
            if block.type == "tool_use" and block.name == "book_flight":
                result = book_flight(**block.input)   # FiGuard runs here

    On denial the function returns a denial string — return it to Claude in a
    ``tool_result`` block so it can reason about the outcome. On tool error the
    original exception is re-raised after notifying FiGuard.

    :param client:         FiGuardClient instance.
    :param session_token:  Budget session token for this agent run.
    :param category:       FiGuard claimed category. Required for allocation budgets.
    :param amount_key:       Name of the kwarg that holds the spend amount.
                             Defaults to ``"amount"``. Ignored if ``amount_extractor`` is set.
    :param agent_id:         Agent identifier written to the FiGuard audit ledger.
    :param amount_extractor: Optional callable ``(**kwargs) -> float`` for custom amount extraction.
    :param debug:            When ``True``, logs category and amount sent to FiGuard.
    """
    def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
        @wraps(fn)
        def wrapper(**kwargs: Any) -> Any:
            amount = _resolve_amount(kwargs, amount_key, amount_extractor)
            if debug:
                logger.info("figuard debug: fn=%s category=%s amount=%s", fn.__name__, category, amount)
            description = f"{fn.__name__}: {str(kwargs)[:200]}"

            auth = client.authorize(
                session_token=session_token,
                agent_id=agent_id,
                action_type="TOOL_CALL",
                description=description,
                requested_quantity=amount,
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
                client.confirm_event(auth.event_id, confirmed_quantity=amount)
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


def _resolve_amount(
    kwargs: Dict[str, Any],
    amount_key: str,
    amount_extractor: Optional[Callable[..., float]],
) -> float:
    if amount_extractor is not None:
        try:
            return float(amount_extractor(**kwargs))
        except Exception:
            return 0.0
    val = kwargs.get(amount_key, 0.0)
    try:
        return float(val)
    except (TypeError, ValueError):
        return 0.0
