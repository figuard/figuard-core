"""
FiGuard integration for LangChain and LangGraph.

Two integration patterns:

**FiGuardCallbackHandler** — attach to any AgentExecutor or LangGraph graph as
a callback. Every tool call is pre-authorized. Requires ``handle_tool_error=True``
on the AgentExecutor so the LLM receives the structured denial reason rather than
the run crashing.

Supports two token modes:

- **Single agent** — pass ``session_token`` directly::

    handler = FiGuardCallbackHandler(
        client=client,
        session_token=budget.primary_token.session_token,
    )

- **Fleet / LangGraph supervisor** — pass a ``token_resolver`` callable that
  maps an agent ID to the right delegation token at runtime. One handler wired
  to the whole graph; the resolver picks the correct scoped token per node::

    handler = FiGuardCallbackHandler(
        client=client,
        token_resolver=lambda agent_id: delegation_tokens[agent_id],
    )

    graph = supervisor_graph.compile(callbacks=[handler])

  Each LangGraph node must pass ``agent_id`` through the run config::

    def researcher_node(state, config):
        return llm.invoke(
            state["messages"],
            config={"metadata": {"agent_id": "researcher"}},
        )

**FiGuardToolGuard** — wraps an individual tool in-place.
Guaranteed hard enforcement regardless of AgentExecutor settings. Use when
you need per-tool control over category or amount extraction, or when only
some tools should be guarded.

Installation::

    pip install figuard[langchain]

---

**Causal chain construction (how parent_event_id is resolved)**

LangChain/LangGraph assigns every execution unit a ``run_id`` UUID and records
its parent via ``parent_run_id``. This maps 1-to-1 onto FiGuard's causal chain:

    run_id        → agentId
    parent_run_id → parentEventId (via the run_id → event_id mapping table)
    run_name      → agentType

The handler builds two in-memory tables per graph execution:

1. ``_run_topology``: ``{run_id → parent_run_id}`` — recorded for every
   ``on_chain_start`` and ``on_tool_start``, even for nodes that never call
   ``authorize``. Required for partial-tree walk-up (see below).

2. ``_run_id_to_event_id``: ``{run_id → FiGuard event_id}`` — populated
   after every successful authorization.

When a tool starts, the handler resolves ``parent_event_id`` by walking up
``_run_topology`` from the tool's ``parent_run_id`` until it finds a run_id
with a known event_id::

    Tool C  (parent_run_id = B)
      → look up B in event table → not found
      → look up B's parent in topology → A
      → look up A in event table → evt_A ✓
      → authorize Tool C with parent_event_id=evt_A

This handles the **partial instrumentation** case where intermediate nodes in
the graph never call authorize — the walk-up skips over them to the nearest
instrumented ancestor.

**Deferred linking (parallel graph race)**

In parallel LangGraph graphs, two nodes can start simultaneously via a thread
pool. Node B's ``on_tool_start`` may fire before Node A's tool has authorized
(so A's event_id is not in the table yet). The handler detects this and buffers
Node B's authorize call. When A's event_id arrives, the buffer is flushed and
B's authorize fires with the correct parent::

    Thread 1: Tool B starts, parent=A → A has no event_id → buffered
    Thread 2: Tool A authorizes → event_id stored → flush buffer → B authorizes

**Thread pool context propagation**

Python ContextVar does NOT propagate into ThreadPoolExecutor threads. Use
``figuard.context.figuard_run_in_executor`` instead of calling the executor
directly for any async graph that fans out to parallel branches.
"""

from __future__ import annotations

import ast
import json
import logging
import threading
from collections import defaultdict
from typing import Any, Callable, Dict, List, Optional, Set, Union
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

    **Single agent** — pass ``session_token``::

        executor = AgentExecutor(
            agent=agent,
            tools=tools,
            handle_tool_error=True,
            callbacks=[FiGuardCallbackHandler(
                client=client,
                session_token=budget.primary_token.session_token,
                tool_category_map={"book_flight": "flight"},
            )],
        )

    **Fleet / LangGraph supervisor** — pass ``token_resolver`` instead.
    One handler covers all sub-agents; the resolver maps each agent's ID to
    its scoped delegation token at runtime::

        handler = FiGuardCallbackHandler(
            client=client,
            token_resolver=lambda agent_id: delegation_tokens[agent_id],
        )
        graph = supervisor_graph.compile(callbacks=[handler])

    Each LangGraph node must pass ``agent_id`` through its run config so the
    handler knows which delegation token to use::

        def researcher_node(state, config):
            return llm.invoke(
                state["messages"],
                config={"metadata": {"agent_id": "researcher"}},
            )

    **What happens on denial:**
    ``on_tool_start`` raises ``ToolException`` before the tool runs. With
    ``handle_tool_error=True``, the LLM receives the denial reason as the
    tool result and can adjust its plan. Without it the run crashes.

    **Amount extraction:**
    The handler looks for a key named ``amount_param`` (default ``"amount"``)
    in the tool's JSON input. Override with ``amount_extractor`` for complex cases.

    **Exact cost confirmation:**
    Provide ``cost_extractor`` to confirm with the actual settled cost instead of
    the authorized amount, releasing any unused reservation::

        handler = FiGuardCallbackHandler(
            client=client,
            session_token=budget.primary_token.session_token,
            cost_extractor=lambda output: json.loads(output)["charged_amount"],
        )
    """

    raise_error: bool = True  # propagate ToolException so denials block execution

    def __init__(
        self,
        client: FiGuardClient,
        session_token: Optional[str] = None,
        *,
        token_resolver: Optional[Callable[[Optional[str]], str]] = None,
        agent_id: str = "langchain_agent",
        amount_param: str = "amount",
        tool_category_map: Optional[Dict[str, str]] = None,
        ignore_tools: Optional[Set[str]] = None,
        amount_extractor: Optional[Callable[[Dict[str, Any]], float]] = None,
        cost_extractor: Optional[Callable[[Any], float]] = None,
        debug: bool = False,
    ) -> None:
        """
        :param client:            FiGuardClient instance.
        :param session_token:     Budget session token for single-agent use.
                                  Mutually exclusive with ``token_resolver``.
        :param token_resolver:    Fleet / LangGraph pattern. Callable
                                  ``(agent_id: str | None) -> str`` that returns
                                  the correct delegation token for each sub-agent.
                                  The handler reads ``agent_id`` from the LangChain
                                  run metadata (set via
                                  ``config={"metadata": {"agent_id": "..."}}``)
                                  and passes it to the resolver on every tool call.
                                  Mutually exclusive with ``session_token``.
        :param agent_id:          Default agent identifier written to the FiGuard
                                  audit ledger. In fleet mode the per-call metadata
                                  agent_id takes precedence when present.
        :param amount_param:      Tool input key that contains the spend amount.
                                  Defaults to ``"amount"``. Ignored if
                                  ``amount_extractor`` is set.
        :param tool_category_map: Maps tool name → FiGuard claimed category.
                                  Required for allocation budgets.
        :param ignore_tools:      Tool names to skip (read-only lookups, etc.).
        :param amount_extractor:  ``(parsed_input: dict) -> float``. Use when the
                                  amount lives under a non-standard key or must be
                                  computed from multiple fields.
        :param cost_extractor:    ``(tool_output: Any) -> float``. Called in
                                  ``on_tool_end`` to extract the actual settled cost.
                                  Falls back to the authorized amount if it raises or
                                  returns a non-positive value.
        :param debug:             Log category and amount for each tool call.
        """
        if not session_token and not token_resolver:
            raise ValueError(
                "FiGuardCallbackHandler requires either session_token (single agent) "
                "or token_resolver (fleet/LangGraph pattern)."
            )
        if session_token and token_resolver:
            raise ValueError(
                "Provide session_token or token_resolver, not both."
            )
        super().__init__()
        self._client = client
        self._session_token = session_token
        self._token_resolver = token_resolver
        self._agent_id = agent_id
        self._amount_param = amount_param
        self._tool_category_map: Dict[str, str] = tool_category_map or {}
        self._ignore_tools: Set[str] = ignore_tools or set()
        self._amount_extractor = amount_extractor
        self._cost_extractor = cost_extractor
        self._debug = debug
        self._lock = threading.Lock()

        # run_id → (event_id, authorized_amount) — live authorizations
        self._pending: Dict[str, tuple[str, float]] = {}

        # Causal chain tables — all guarded by _lock
        # Maps every observed run_id → its parent_run_id (including non-instrumented nodes)
        # Used for partial-tree walk-up when intermediate nodes don't call authorize.
        self._run_topology: Dict[str, Optional[str]] = {}
        # Maps run_id → FiGuard event_id once that run's tool has been authorized.
        self._run_id_to_event_id: Dict[str, str] = {}
        # Deferred children: parent_run_id → list of (child_run_id, resolve_callback)
        # Populated when a tool fires before its parent's event_id is known (parallel graph race).
        self._deferred: Dict[str, List[Callable[[str], None]]] = defaultdict(list)

    def _get_token(self, metadata_agent_id: Optional[str]) -> str:
        """Return the session token to use for this tool call."""
        if self._session_token:
            return self._session_token
        if metadata_agent_id is None:
            logger.warning(
                "figuard: token_resolver is set but no agent_id found in run metadata. "
                "Pass agent_id via config={'metadata': {'agent_id': '...'}} in your "
                "LangGraph node. Calling resolver with None."
            )
        token = self._token_resolver(metadata_agent_id)  # type: ignore[misc]
        if not token:
            raise ValueError(
                f"figuard: token_resolver returned empty token for "
                f"agent_id={metadata_agent_id!r}"
            )
        return token

    # ------------------------------------------------------------------
    # Topology tracking
    # ------------------------------------------------------------------

    def _record_topology(self, run_id: UUID, parent_run_id: Optional[UUID]) -> None:
        """
        Record run_id → parent_run_id for every execution unit, even those that
        never call authorize. This lets _resolve_parent_event_id walk up past
        uninstrumented intermediate nodes.
        """
        with self._lock:
            self._run_topology[str(run_id)] = (
                str(parent_run_id) if parent_run_id else None
            )

    def _resolve_parent_event_id(self, parent_run_id: Optional[UUID]) -> Optional[str]:
        """
        Walk up the run topology tree to find the nearest ancestor run_id that
        has a known FiGuard event_id.

        Handles three cases:
        - Sequential graph: parent's event_id is already in the table → immediate hit.
        - Partial instrumentation: intermediate nodes that never authorize are
          skipped until an instrumented ancestor is found.
        - Parallel graph race: parent's event_id hasn't arrived yet → returns None
          and the caller must register a deferred callback.
        """
        if parent_run_id is None:
            return None
        with self._lock:
            current = str(parent_run_id)
            while current is not None:
                if current in self._run_id_to_event_id:
                    return self._run_id_to_event_id[current]
                current = self._run_topology.get(current)
        return None

    def _register_event_id(self, run_id: UUID, event_id: str) -> None:
        """
        Store a run_id → event_id mapping and flush any deferred children that
        were waiting on this run_id's event_id to arrive.
        """
        run_id_str = str(run_id)
        deferred_callbacks: List[Callable[[str], None]] = []
        with self._lock:
            self._run_id_to_event_id[run_id_str] = event_id
            deferred_callbacks = self._deferred.pop(run_id_str, [])

        # Call deferred callbacks outside the lock to avoid deadlock
        for callback in deferred_callbacks:
            try:
                callback(event_id)
            except Exception as exc:
                logger.warning("figuard: deferred callback failed: %s", exc)

    # ------------------------------------------------------------------
    # Callback lifecycle
    # ------------------------------------------------------------------

    def on_chain_start(
        self,
        serialized: Dict[str, Any],
        inputs: Dict[str, Any],
        *,
        run_id: UUID,
        parent_run_id: Optional[UUID] = None,
        **kwargs: Any,
    ) -> None:
        """
        Record every chain/node start in the topology table — even if the node
        never calls authorize. Required for partial-tree walk-up in graphs where
        only some nodes are instrumented.
        """
        self._record_topology(run_id, parent_run_id)

    def on_tool_start(
        self,
        serialized: Dict[str, Any],
        input_str: str,
        *,
        run_id: UUID,
        parent_run_id: Optional[UUID] = None,
        **kwargs: Any,
    ) -> None:
        """
        Called before a tool runs. Resolves parent_event_id via causal chain
        topology, then authorizes. Raises ToolException on denial so the LLM
        receives the denial reason instead of a crash.
        """
        tool_name = serialized.get("name", "unknown_tool")

        if tool_name in self._ignore_tools:
            logger.debug("figuard: skipping %s (in ignore_tools)", tool_name)
            return

        # Record this tool's position in the topology (parent_run_id = the chain/node that called it)
        self._record_topology(run_id, parent_run_id)

        metadata = kwargs.get("metadata") or {}
        metadata_agent_id: Optional[str] = metadata.get("agent_id") if isinstance(metadata, dict) else None
        # Resolution order: per-call metadata → handler default → run_id fallback
        effective_agent_id = metadata_agent_id or self._agent_id or str(run_id)

        token = self._get_token(metadata_agent_id)
        parsed = _parse_input(input_str)
        amount = _resolve_amount(parsed, self._amount_param, self._amount_extractor)
        category = self._tool_category_map.get(tool_name)

        if self._debug:
            logger.info(
                "figuard debug: tool=%s agent=%s category=%s amount=%s run_id=%s parent=%s",
                tool_name, effective_agent_id, category, amount, run_id, parent_run_id,
            )

        # Resolve parent_event_id from the causal chain topology.
        # Walk up from parent_run_id until we find an ancestor with a known event_id.
        resolved_parent = self._resolve_parent_event_id(parent_run_id)

        if resolved_parent is None and parent_run_id is not None:
            # Parent event_id not yet available — parallel graph race condition.
            # We cannot defer here (on_tool_start is sync and must decide immediately)
            # so we authorize without parent and log the gap. The deferred mechanism
            # is used for scenarios where authorize() itself is called outside callbacks.
            logger.debug(
                "figuard: parent run_id=%s has no event_id yet (parallel race) — "
                "authorizing tool=%s as root event",
                parent_run_id, tool_name,
            )

        auth = self._client.authorize(
            session_token=token,
            agent_id=effective_agent_id,
            action_type="TOOL_CALL",
            description=f"{tool_name}: {input_str[:200]}",
            requested_quantity=amount,
            claimed_category=category,
            parent_event_id=resolved_parent,  # explicit — takes precedence over ambient ContextVar
            agent_type="langchain_tool",
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
            "figuard: AUTHORIZED tool=%s event_id=%s amount=%.2f parent=%s",
            tool_name, auth.event_id, amount, resolved_parent,
        )

        # Store event_id for this run_id and flush any deferred children waiting on it
        self._register_event_id(run_id, auth.event_id)

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

        event_id, authorized_amount = pending
        confirmed_amount = authorized_amount

        if self._cost_extractor is not None:
            try:
                extracted = float(self._cost_extractor(output))
                if extracted > 0:
                    confirmed_amount = extracted
                    if self._debug:
                        logger.info(
                            "figuard debug: cost_extractor returned %.4f (authorized=%.4f)",
                            extracted, authorized_amount,
                        )
                else:
                    logger.warning(
                        "figuard: cost_extractor returned non-positive value %.4f "
                        "for event_id=%s — confirming with authorized amount %.4f",
                        extracted, event_id, authorized_amount,
                    )
            except Exception as exc:
                logger.warning(
                    "figuard: cost_extractor raised %s for event_id=%s "
                    "— confirming with authorized amount %.4f",
                    exc, event_id, authorized_amount,
                )

        try:
            self._client.confirm_event(event_id, confirmed_quantity=confirmed_amount)
            logger.debug(
                "figuard: CONFIRMED event_id=%s confirmed=%.4f authorized=%.4f",
                event_id, confirmed_amount, authorized_amount,
            )
        except Exception as exc:
            logger.warning("figuard: confirm failed event_id=%s: %s", event_id, exc)

    def on_tool_error(
        self,
        error: BaseException,
        *,
        run_id: UUID,
        parent_run_id: Optional[UUID] = None,
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
            session_token=budget.primary_token.session_token,
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
        amount_extractor: Optional[Callable[..., float]] = None,
        debug: bool = False,
    ) -> None:
        """
        :param tool:             LangChain BaseTool to wrap. Modified in-place.
        :param client:           FiGuardClient instance.
        :param session_token:    Budget session token for this agent run.
        :param category:         FiGuard claimed category for this tool's spend.
                                 Required for allocation budgets.
        :param amount_key:       Keyword argument name that contains the spend amount.
                                 Defaults to ``"amount"``. Ignored if ``amount_extractor`` is set.
        :param agent_id:         Agent identifier written to the FiGuard audit ledger.
        :param amount_extractor: Optional callable ``(**kwargs) -> float``.
                                 Use when the amount must be computed from multiple kwargs
                                 or lives under a non-standard name.
                                 Example: ``amount_extractor=lambda **kw: kw.get("price", 0)``
        :param debug:            When ``True``, logs the category and amount being sent to FiGuard.
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

    def _guarded_run(self, *args: Any, **kwargs: Any) -> Any:
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
            result = self._original_run(*args, **kwargs)
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


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_input(input_str: str) -> Dict[str, Any]:
    """
    Parse a tool's input string to a dict.

    Tries JSON first, then ast.literal_eval for the Python repr format
    (single-quoted keys) that LangGraph sometimes produces. Returns {} on failure.
    """
    try:
        data = json.loads(input_str)
        if isinstance(data, dict):
            return data
    except (json.JSONDecodeError, TypeError, ValueError):
        pass
    try:
        data = ast.literal_eval(input_str)
        if isinstance(data, dict):
            return data
    except (ValueError, SyntaxError):
        pass
    return {}


def _resolve_amount(
    kwargs: Dict[str, Any],
    amount_key: str,
    amount_extractor: Optional[Callable[..., float]],
) -> float:
    """
    Extract spend amount from tool kwargs.

    If ``amount_extractor`` is provided, calls it with the kwargs dict and
    returns the result. Otherwise looks up ``amount_key`` in kwargs.
    Returns 0.0 if nothing matches — a 0.0 authorization is audit-only.
    """
    if amount_extractor is not None:
        try:
            return float(amount_extractor(kwargs))
        except Exception:
            return 0.0
    val = kwargs.get(amount_key, 0.0)
    try:
        return float(val)
    except (TypeError, ValueError):
        return 0.0


def auto_guard_langchain(
    executor: Any,
    budget: float = 500,
    currency: str = "USD",
    agent_id: str = "langchain_agent",
    amount_param: str = "amount",
    velocity_max_per_minute: Optional[int] = None,
    client: Optional[FiGuardClient] = None,
) -> Any:
    """
    One-line FiGuard wiring for a LangChain AgentExecutor.

    Creates a FiGuardClient (zero-config), provisions a 24-hour budget, attaches
    a FiGuardCallbackHandler, and returns the same executor — ready to run.

    Usage::

        from figuard.integrations.langchain import auto_guard_langchain

        # Monetary budget — enforces dollar spend on tools with an "amount" parameter
        executor = auto_guard_langchain(executor, budget=500, currency="USD")

        # Velocity control — catches runaway loops even when tool calls have no dollar amount
        executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)

        result = executor.invoke({"input": "Book a flight to NYC"})

    :param executor:               LangChain AgentExecutor to wire up. Modified in-place.
    :param budget:                 Total spend limit in ``currency`` units (default 500).
    :param currency:               ISO 4217 currency code (default "USD").
    :param agent_id:               Agent identifier written to the FiGuard audit ledger.
    :param amount_param:           Tool input key that contains the spend amount (default "amount").
                                   Returns 0.0 (audit-only) when the key is absent.
    :param velocity_max_per_minute: Max tool calls per 60-second window. Use when tools
                                   lack a dollar amount — catches runaway loops by rate.
    :param client:                 Optional pre-built FiGuardClient. Pass one to reuse an
                                   existing client or to point at a server/sandbox instead of
                                   the embedded (local SQLite) default.
    :returns:                      The same ``executor`` with FiGuardCallbackHandler wired in.

    For per-category allocations, anomaly detection, entity dedup, custom ``user_id``, or
    budgets that last longer than 24 hours — create the budget manually and use
    ``FiGuardCallbackHandler`` directly. See docs/integrations/langchain.md.
    """
    _client = client or FiGuardClient()
    _budget = _client.create_budget(
        user_id=str(uuid4()),
        total_limit=budget,
        currency=currency,
        expires_in="24h",
        velocity_max_per_minute=velocity_max_per_minute,
    )
    handler = FiGuardCallbackHandler(
        client=_client,
        session_token=_budget.primary_token.session_token,
        agent_id=agent_id,
        amount_param=amount_param,
    )
    if not hasattr(executor, "callbacks") or executor.callbacks is None:
        executor.callbacks = [handler]
    else:
        executor.callbacks = list(executor.callbacks) + [handler]
    return executor
