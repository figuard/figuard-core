"""
FiGuard integration for LangChain and LangGraph.

Two integration patterns:

**FiGuardCallbackHandler** — attach to any AgentExecutor as a callback.
Every tool call is pre-authorized. Requires ``handle_tool_error=True`` on the
AgentExecutor so the LLM receives the structured denial reason rather than
the run crashing.

**FiGuardToolGuard** — wraps an individual tool in-place.
Guaranteed hard enforcement regardless of AgentExecutor settings. Use when
you need per-tool control over category or amount extraction, or when only
some tools should be guarded.

Installation::

    pip install figuard[langchain]

Quick start::

    from figuard import FiGuardClient
    from figuard.integrations.langchain import FiGuardCallbackHandler

    client = FiGuardClient(api_key="ab_live_demo")
    budget = client.create_budget(user_id="user_123", total_limit=500.00, ...)

    executor = AgentExecutor(
        agent=agent,
        tools=tools,
        handle_tool_error=True,
        callbacks=[FiGuardCallbackHandler(
            client=client,
            session_token=budget.session_token,
            tool_category_map={"book_flight": "flight", "book_hotel": "hotel"},
        )],
    )
"""

from __future__ import annotations

import json
import logging
import threading
from typing import Any, Dict, Optional, Set, Union
from uuid import UUID, uuid4

try:
    from langchain_core.callbacks import BaseCallbackHandler
    from langchain_core.tools import BaseTool, ToolException
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "FiGuard LangChain integration requires langchain-core. "
        "Install it with: pip install figuard[langchain]"
    ) from exc

from figuard.client import FiGuardClient

logger = logging.getLogger(__name__)


class FiGuardCallbackHandler(BaseCallbackHandler):
    """
    LangChain callback handler that pre-authorizes every tool call via FiGuard.

    Attach to an ``AgentExecutor`` via its ``callbacks`` parameter::

        executor = AgentExecutor(
            agent=agent,
            tools=tools,
            handle_tool_error=True,       # required — sends denial to LLM
            callbacks=[FiGuardCallbackHandler(
                client=client,
                session_token=budget.session_token,
                tool_category_map={
                    "book_flight": "flight",
                    "book_hotel":  "hotel",
                },
            )],
        )

    **What happens on denial:**
    ``on_tool_start`` raises ``ToolException`` before the tool runs. With
    ``handle_tool_error=True``, the LLM receives the denial reason as the
    tool result and can adjust its plan (e.g. try a cheaper option, escalate,
    or stop). Without it, the run crashes — so always set it.

    **Amount extraction:**
    The handler looks for a key named ``amount_param`` (default ``"amount"``)
    in the tool's JSON input. If your tool uses a different key (e.g. ``"price"``),
    either set ``amount_param`` globally or use ``FiGuardToolGuard`` per tool.

    **Non-spending tools:**
    Pass tool names to ``ignore_tools`` to skip authorization for search,
    retrieval, or other read-only operations.
    """

    raise_error: bool = True  # propagate ToolException so denials block execution

    def __init__(
        self,
        client: FiGuardClient,
        session_token: str,
        *,
        agent_id: str = "langchain_agent",
        amount_param: str = "amount",
        tool_category_map: Optional[Dict[str, str]] = None,
        ignore_tools: Optional[Set[str]] = None,
    ) -> None:
        """
        :param client:            FiGuardClient instance.
        :param session_token:     Budget session token for this agent run.
        :param agent_id:          Agent identifier written to the FiGuard audit ledger.
        :param amount_param:      Tool input key that contains the spend amount.
                                  Defaults to ``"amount"``.
        :param tool_category_map: Maps tool name to FiGuard claimed category.
                                  Required for allocation budgets.
                                  Example: ``{"book_flight": "flight"}``
        :param ignore_tools:      Tool names to skip authorization entirely
                                  (search tools, read-only lookups, etc.).
        """
        super().__init__()
        self._client = client
        self._session_token = session_token
        self._agent_id = agent_id
        self._amount_param = amount_param
        self._tool_category_map: Dict[str, str] = tool_category_map or {}
        self._ignore_tools: Set[str] = ignore_tools or set()
        # run_id → (event_id, amount) — populated on authorize, consumed on confirm/fail
        self._pending: Dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Callback lifecycle
    # ------------------------------------------------------------------

    def on_tool_start(
        self,
        serialized: Dict[str, Any],
        input_str: str,
        *,
        run_id: UUID,
        **kwargs: Any,
    ) -> None:
        """Called before a tool runs. Authorizes the call; raises on denial."""
        tool_name = serialized.get("name", "unknown_tool")

        if tool_name in self._ignore_tools:
            logger.debug("figuard: skipping %s (in ignore_tools)", tool_name)
            return

        amount = _extract_amount(input_str, self._amount_param)
        category = self._tool_category_map.get(tool_name)

        auth = self._client.authorize(
            session_token=self._session_token,
            agent_id=self._agent_id,
            action_type="TOOL_CALL",
            description=f"{tool_name}: {input_str[:200]}",
            requested_amount=amount,
            claimed_category=category,
            idempotency_key=str(run_id),
        )

        if not auth.is_authorized:
            logger.info(
                "figuard: DENIED tool=%s reason=%s event_id=%s",
                tool_name, auth.denial_reason, auth.event_id,
            )
            msg = f"FiGuard DENIED: {auth.denial_reason}"
            if auth.denial_message:
                msg += f" — {auth.denial_message}"
            raise ToolException(msg)

        logger.debug(
            "figuard: AUTHORIZED tool=%s event_id=%s amount=%.2f",
            tool_name, auth.event_id, amount,
        )
        with self._lock:
            self._pending[str(run_id)] = (auth.event_id, amount)

    def on_tool_end(
        self,
        output: Any,
        *,
        run_id: UUID,
        **kwargs: Any,
    ) -> None:
        """Called after a tool runs successfully. Confirms the authorization."""
        with self._lock:
            pending = self._pending.pop(str(run_id), None)
        if pending is None:
            return  # tool was ignored or denied — nothing to confirm

        event_id, amount = pending
        try:
            self._client.confirm_event(event_id, confirmed_amount=amount)
            logger.debug("figuard: CONFIRMED event_id=%s", event_id)
        except Exception as exc:
            logger.warning("figuard: confirm failed event_id=%s: %s", event_id, exc)

    def on_tool_error(
        self,
        error: Union[Exception, KeyboardInterrupt],
        *,
        run_id: UUID,
        **kwargs: Any,
    ) -> None:
        """Called when a tool raises an exception. Marks the authorization as failed."""
        with self._lock:
            pending = self._pending.pop(str(run_id), None)
        if pending is None:
            return  # tool was denied or ignored — no live authorization to fail

        event_id, _ = pending
        try:
            self._client.fail_event(
                event_id=event_id,
                reason="TOOL_ERROR",
                error_message=str(error)[:500],
            )
            logger.debug("figuard: FAILED event_id=%s", event_id)
        except Exception as exc:
            logger.warning("figuard: fail call failed event_id=%s: %s", event_id, exc)


class FiGuardToolGuard:
    """
    Wraps a single LangChain tool with FiGuard authorization.

    Patches the tool's ``_run`` method in-place. After wrapping, pass the tool
    to your agent as normal — no other changes required.

    Unlike ``FiGuardCallbackHandler``, this guarantees that a denied tool
    never executes regardless of ``AgentExecutor`` settings. Denial returns a
    structured string to the LLM so it can reason about the outcome.

    Usage::

        from figuard.integrations.langchain import FiGuardToolGuard

        FiGuardToolGuard(
            tool=book_flight_tool,
            client=client,
            session_token=budget.session_token,
            category="flight",
            amount_key="price",     # key in tool kwargs that holds the spend amount
        )

        # book_flight_tool is now guarded — pass to create_react_agent as usual
        agent = create_react_agent(llm=llm, tools=[book_flight_tool, ...])

    Denial example returned to the LLM::

        "FiGuard DENIED: NO_MATCHING_ALLOCATION — flight is not in this budget"
    """

    def __init__(
        self,
        tool: BaseTool,
        client: FiGuardClient,
        session_token: str,
        *,
        category: Optional[str] = None,
        amount_key: str = "amount",
        agent_id: str = "langchain_agent",
    ) -> None:
        """
        :param tool:           LangChain BaseTool to wrap. Modified in-place.
        :param client:         FiGuardClient instance.
        :param session_token:  Budget session token for this agent run.
        :param category:       FiGuard claimed category for this tool's spend.
                               Required for allocation budgets.
        :param amount_key:     Keyword argument name that contains the spend amount.
                               Defaults to ``"amount"``.
        :param agent_id:       Agent identifier written to the FiGuard audit ledger.
        """
        self._tool = tool
        self._client = client
        self._session_token = session_token
        self._category = category
        self._amount_key = amount_key
        self._agent_id = agent_id

        # Wrap _run in-place — the tool itself is unchanged from the agent's perspective
        self._original_run = tool._run
        tool._run = self._guarded_run

    def _guarded_run(self, *args: Any, **kwargs: Any) -> Any:
        amount = float(kwargs.get(self._amount_key, 0.0))
        description = f"{self._tool.name}: {str(kwargs)[:200]}"

        auth = self._client.authorize(
            session_token=self._session_token,
            agent_id=self._agent_id,
            action_type="TOOL_CALL",
            description=description,
            requested_amount=amount,
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
            result = self._original_run(*args, **kwargs)
            self._client.confirm_event(auth.event_id, confirmed_amount=amount)
            logger.debug(
                "figuard: CONFIRMED tool=%s event_id=%s", self._tool.name, auth.event_id
            )
            return result
        except Exception as exc:
            self._client.fail_event(
                event_id=auth.event_id,
                reason="TOOL_ERROR",
                error_message=str(exc)[:500],
            )
            raise


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _extract_amount(input_str: str, amount_param: str) -> float:
    """
    Extract a numeric spend amount from a tool's input string.

    Attempts JSON parse first. Returns 0.0 if the key is not present or the
    input is not valid JSON. A 0.0 amount still authorizes against FiGuard —
    use ``FiGuardToolGuard`` with an explicit ``amount_key`` when the tool input
    does not use a standard ``amount`` key.
    """
    try:
        data = json.loads(input_str)
        if isinstance(data, dict):
            val = data.get(amount_param)
            if val is not None:
                return float(val)
    except (json.JSONDecodeError, TypeError, ValueError):
        pass
    return 0.0
