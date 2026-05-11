"""
FiGuard integration for CrewAI.

Wraps individual CrewAI tools (``BaseTool`` subclasses or ``@tool``-decorated
functions) with FiGuard pre-flight authorization.

Installation::

    pip install figuard[crewai]

Quick start::

    from figuard import FiGuardClient
    from figuard.integrations.crewai import FiGuardCrewGuard

    client = FiGuardClient(api_key="ab_live_demo")
    budget = client.create_budget(user_id="user_123", total_limit=500.00, ...)

    FiGuardCrewGuard(
        tool=book_flight_tool,
        client=client,
        session_token=budget.session_token,
        category="flight",
        amount_key="price",
    )

    # book_flight_tool is now guarded — pass to your CrewAI agent as normal
    travel_agent = Agent(
        role="Travel Coordinator",
        tools=[book_flight_tool, book_hotel_tool],
        ...
    )

**What happens on denial:**
``_run`` returns a structured denial string so the CrewAI LLM can reason about
the outcome and try an alternative (cheaper option, different category, or stop)::

    "FiGuard DENIED: INSUFFICIENT_FUNDS — budget exhausted for this category"

**Amount extraction:**
The guard reads the keyword argument named ``amount_key`` (default ``"amount"``)
directly from the kwargs passed to ``_run``. If your tool uses a different
parameter name (e.g. ``"price"``), set ``amount_key`` accordingly.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Dict, Optional
from uuid import uuid4

try:
    from crewai.tools import BaseTool
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "FiGuard CrewAI integration requires crewai. "
        "Install it with: pip install figuard[crewai]"
    ) from exc

from figuard.client import FiGuardClient

logger = logging.getLogger(__name__)


class FiGuardCrewGuard:
    """
    Wraps a single CrewAI tool with FiGuard authorization.

    Patches the tool's ``_run`` method in-place. After wrapping, pass the tool
    to your CrewAI ``Agent`` as normal — no other changes required.

    Unlike a middleware approach, this guarantees that a denied tool never
    executes regardless of the crew configuration. Denial returns a structured
    string to the LLM so it can reason about the outcome.

    Usage::

        from figuard.integrations.crewai import FiGuardCrewGuard

        FiGuardCrewGuard(
            tool=book_flight_tool,
            client=client,
            session_token=budget.session_token,
            category="flight",
            amount_key="price",
        )

        travel_agent = Agent(
            role="Travel Coordinator",
            tools=[book_flight_tool, book_hotel_tool],
        )
    """

    def __init__(
        self,
        tool: BaseTool,
        client: FiGuardClient,
        session_token: str,
        *,
        category: Optional[str] = None,
        amount_key: str = "amount",
        agent_id: str = "crewai_agent",
        amount_extractor: Optional[Callable[..., float]] = None,
        debug: bool = False,
    ) -> None:
        """
        :param tool:             CrewAI BaseTool to wrap. Modified in-place.
        :param client:           FiGuardClient instance.
        :param session_token:    Budget session token for this agent run.
        :param category:         FiGuard claimed category for this tool's spend.
                                 Required for allocation budgets.
        :param amount_key:       Keyword argument name that contains the spend amount.
                                 Defaults to ``"amount"``. Ignored if ``amount_extractor`` is set.
        :param agent_id:         Agent identifier written to the FiGuard audit ledger.
        :param amount_extractor: Optional callable ``(**kwargs) -> float`` for custom amount extraction.
        :param debug:            When ``True``, logs category and amount sent to FiGuard.
        """
        self._tool = tool
        self._client = client
        self._session_token = session_token
        self._category = category
        self._amount_key = amount_key
        self._agent_id = agent_id
        self._amount_extractor = amount_extractor
        self._debug = debug

        # Wrap _run in-place — the tool itself is unchanged from the agent's perspective
        self._original_run = tool._run
        object.__setattr__(tool, "_run", self._guarded_run)

    def _guarded_run(self, **kwargs: Any) -> Any:
        amount = _resolve_amount(kwargs, self._amount_key, self._amount_extractor)
        if self._debug:
            logger.info("figuard debug: tool=%s category=%s amount=%s", self._tool.name, self._category, amount)
        description = f"{self._tool.name}: {str(kwargs)[:200]}"

        auth = self._client.authorize(
            session_token=self._session_token,
            agent_id=self._agent_id,
            action_type="TOOL_CALL",
            description=description,
            requested_quantity=amount,
            claimed_category=self._category,
            idempotency_key=str(uuid4()),
        )

        if not auth.is_authorized:
            msg = f"FiGuard DENIED: {auth.denial_reason}"
            if auth.denial_message:
                msg += f" — {auth.denial_message}"
            logger.info(
                "figuard: DENIED tool=%s reason=%s event_id=%s",
                self._tool.name, auth.denial_reason, auth.event_id,
            )
            return msg

        try:
            result = self._original_run(**kwargs)
        except Exception as exc:
            self._client.fail_event(
                event_id=auth.event_id,
                reason="TOOL_ERROR",
                error_message=str(exc)[:500],
            )
            raise

        try:
            self._client.confirm_event(auth.event_id, confirmed_quantity=amount)
            logger.debug(
                "figuard: CONFIRMED tool=%s event_id=%s", self._tool.name, auth.event_id
            )
        except Exception as exc:
            logger.warning(
                "figuard: confirm failed tool=%s event_id=%s: %s",
                self._tool.name, auth.event_id, exc,
            )

        return result


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
