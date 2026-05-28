"""
FiGuard asynchronous Python client.

Usage::

    from figuard import AsyncFiGuardClient

    async with AsyncFiGuardClient() as client:  # zero-config: sandbox fallback
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            currency="USD",
            expires_at="2024-12-31T23:59:59Z",
        )

        result = await client.authorize(
            session_token=budget.primary_token.session_token,
            agent_id="agent_flight_booker",
            action_type="PURCHASE",
            description="Book NYC flight",
            requested_quantity=299.00,
            currency="USD",
            idempotency_key="txn-abc-001",
        )

        if result.is_authorized:
            await client.confirm_event(result.event_id, confirmed_quantity=299.00)

Designed for frameworks that run on asyncio: LangChain, CrewAI, OpenAI Agents SDK,
FastAPI background tasks, etc.

Install the async extra::

    pip install figuard[async]
"""

from __future__ import annotations

import asyncio
import logging
import os
import uuid
from typing import Any, AsyncIterator, Dict, List, Optional, Union
from datetime import timedelta

try:
    import aiohttp
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "AsyncFiGuardClient requires aiohttp. "
        "Install it with: pip install figuard[async]"
    ) from exc

from .client import _resolve_expires_at
from .exceptions import FiGuardApiError, FiGuardConnectionError
from .models import (
    AllocationResponse,
    AllocationSnapshot,
    AuthorizationResult,
    Budget,
    BudgetSnapshot,
    DelegationToken,
    LedgerPage,
    SpendEventResponse,
    SpendTree,
    SpendTreeNode,
    VoidResult,
    VoidTreeResult,
)
from .client import (
    _parse_authorization_result,
    _parse_budget,
    _parse_delegation_token,
    _parse_spend_event,
    _parse_tree_node,
)
from .context import _set_current_event_id, get_current_event_id
from .telemetry import (
    authorize_span,
    finish_authorize_span,
    lifecycle_span,
    void_tree_span,
    finish_void_tree_span,
    get_current_trace_id,
)

logger = logging.getLogger(__name__)

_MAX_RETRIES = 3
_RETRY_BACKOFF_BASE = 1.0  # seconds; doubles each retry (1s, 2s, 4s)


class AsyncFiGuardClient:
    """
    Asynchronous FiGuard API client backed by ``aiohttp``.

    Supports ``async with`` for managed session lifecycle::

        async with AsyncFiGuardClient() as client:  # zero-config sandbox
            budget = await client.create_budget(...)

    Or construct manually and call ``await client.close()`` when done::

        client = AsyncFiGuardClient()
        try:
            budget = await client.create_budget(...)
        finally:
            await client.close()

    Configuration resolution order (same as ``FiGuardClient``):

    1. Explicit ``api_key`` / ``base_url`` parameters
    2. ``FIGUARD_API_KEY`` / ``FIGUARD_BASE_URL`` environment variables
    3. Shared public sandbox (``sb_live_demo``)

    :param api_key:  Your ``fg_live_...`` or ``fg_test_...`` API key. Optional.
    :param base_url: Override for self-hosted deployments. Optional.
    :param timeout:  Per-request timeout in seconds (default 30).
    """

    _SANDBOX_API_KEY = "sb_live_demo"
    _SANDBOX_BASE_URL = "https://figuard-sandbox-g1ha.onrender.com"

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout: int = 30,
    ) -> None:
        resolved_key = api_key or os.environ.get("FIGUARD_API_KEY")
        resolved_url = base_url or os.environ.get("FIGUARD_BASE_URL")
        self._api_key = resolved_key or self._SANDBOX_API_KEY
        self._base_url = (resolved_url or self._SANDBOX_BASE_URL).rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._default_headers: Dict[str, str] = {
            "X-Agent-Budget-Key": self._api_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        self._session: Optional[aiohttp.ClientSession] = None

    # -----------------------------------------------------------------------
    # Context manager
    # -----------------------------------------------------------------------

    async def __aenter__(self) -> "AsyncFiGuardClient":
        self._session = aiohttp.ClientSession(
            headers=self._default_headers,
            timeout=self._timeout,
        )
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self.close()

    async def close(self) -> None:
        """Close the underlying aiohttp session."""
        if self._session is not None and not self._session.closed:
            await self._session.close()
            self._session = None

    def _get_session(self) -> aiohttp.ClientSession:
        """Return existing session or create one if called outside ``async with``."""
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers=self._default_headers,
                timeout=self._timeout,
            )
        return self._session

    # -----------------------------------------------------------------------
    # Budget management
    # -----------------------------------------------------------------------

    async def create_budget(
        self,
        user_id: str,
        total_limit: float,
        expires_at: Optional[str] = None,
        expires_in: Optional[Union[str, int, timedelta]] = None,
        currency: Optional[str] = None,
        unit: Optional[str] = None,
        intent_context: Optional[str] = None,
        intent_tags: Optional[List[str]] = None,
        external_reference: Optional[str] = None,
        soft_limit: Optional[float] = None,
        max_transaction_quantity: Optional[float] = None,
        authorization_expiry_seconds: Optional[int] = None,
        velocity_max_per_minute: Optional[int] = None,
        velocity_max_amount_per_hour: Optional[float] = None,
        velocity_max_per_day: Optional[int] = None,
        anomaly_detection_enabled: bool = False,
        auto_pause_on_anomaly: bool = True,
        entity_dedup_enabled: bool = False,
        allocations: Optional[List[Dict[str, Any]]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Budget:
        """
        Create a new agent budget.

        Exactly one of ``currency`` or ``unit`` must be provided:
        - Monetary budgets: pass ``currency="USD"`` (3-letter ISO code).
        - Resource budgets: pass ``unit="tokens"`` (free-form label).

        :param authorization_expiry_seconds: If set, AUTHORIZED events older than
            this many seconds are excluded from the reserved quantity calculation,
            effectively recycling stale reservations back into the available pool.
        :param velocity_max_per_minute: If set, denies authorization when the number
            of authorized events in the last minute exceeds this value.
        :param velocity_max_amount_per_hour: If set, denies authorization when the
            total authorized amount in the last hour exceeds this value.
        :param velocity_max_per_day: If set, denies authorization when the number
            of authorized events in the last day exceeds this value.
        :param entity_dedup_enabled: If True, a second authorize with the same
            ``entity_id`` is denied with ENTITY_ALREADY_AUTHORIZED instead of
            creating a new event. Use to prevent double-refund on the same order.

        :returns: ``Budget`` with ``tokens`` populated — store
                  ``budget.primary_token.session_token`` securely; it is never returned again.
        """
        body: Dict[str, Any] = {
            "userId": user_id,
            "totalLimit": total_limit,
            "expiresAt": _resolve_expires_at(expires_at, expires_in),
        }
        if currency is not None:
            body["currency"] = currency
        if unit is not None:
            body["unit"] = unit
        if intent_context is not None:
            body["intentContext"] = intent_context
        if intent_tags is not None:
            body["intentTags"] = intent_tags
        if external_reference is not None:
            body["externalReference"] = external_reference
        if soft_limit is not None:
            body["softLimit"] = soft_limit
        if max_transaction_quantity is not None:
            body["maxTransactionQuantity"] = max_transaction_quantity
        if authorization_expiry_seconds is not None:
            body["authorizationExpirySeconds"] = authorization_expiry_seconds
        if velocity_max_per_minute is not None:
            body["velocityMaxPerMinute"] = velocity_max_per_minute
        if velocity_max_amount_per_hour is not None:
            body["velocityMaxAmountPerHour"] = velocity_max_amount_per_hour
        if velocity_max_per_day is not None:
            body["velocityMaxPerDay"] = velocity_max_per_day
        if anomaly_detection_enabled:
            body["anomalyDetectionEnabled"] = True
        if not auto_pause_on_anomaly:
            body["autoPauseOnAnomaly"] = False
        if entity_dedup_enabled:
            body["entityDedupEnabled"] = True
        if allocations is not None:
            body["allocations"] = allocations
        if metadata is not None:
            body["metadata"] = metadata

        data = await self._request("POST", "/api/v1/budgets", json=body, retryable=True)
        return _parse_budget(data)

    async def get_budget(self, budget_id: str) -> Budget:
        """Fetch the current state of a budget."""
        data = await self._request("GET", f"/api/v1/budgets/{budget_id}", retryable=True)
        return _parse_budget(data)

    async def resume_budget(
        self,
        budget_id: str,
        override_reason: str,
        override_by: Optional[str] = None,
    ) -> Budget:
        """
        Resume a PAUSED budget.

        :param override_reason: Required human-readable reason for the override.
        :param override_by:     Optional identifier for the operator or system
                                performing the override.
        :raises FiGuardApiError: HTTP 409 if budget is not currently PAUSED.
        """
        body: Dict[str, Any] = {"overrideReason": override_reason}
        if override_by is not None:
            body["overrideBy"] = override_by
        data = await self._request(
            "POST", f"/api/v1/budgets/{budget_id}/resume", json=body, retryable=True
        )
        return _parse_budget(data)

    async def extend_budget(
        self,
        budget_id: str,
        expires_at: Optional[str] = None,
        expires_in: Optional[Union[str, int, timedelta]] = None,
    ) -> Budget:
        """
        Extend a budget's expiry window.

        The new ``expires_at`` must be later than the current one and at most 24 hours
        from now. Can be called repeatedly to keep a long-running agent alive.

        :raises FiGuardApiError: HTTP 409 if budget is CANCELLED or EXHAUSTED.
        :raises FiGuardApiError: HTTP 400 if ``expires_at`` is before the current one.
        """
        body: Dict[str, Any] = {
            "expiresAt": _resolve_expires_at(expires_at, expires_in),
        }
        data = await self._request(
            "POST", f"/api/v1/budgets/{budget_id}/extend", json=body, retryable=False
        )
        return _parse_budget(data)

    async def cancel_batch(self, budget_ids: List[str]) -> List[Budget]:
        """
        Cancel up to 100 budgets in a single call.

        Already-terminal budgets are included in the response without an error.

        :param budget_ids: List of budget IDs to cancel. Maximum 100.
        :raises FiGuardApiError: HTTP 400 if the list is empty or exceeds 100 items.
        :returns: List of updated ``Budget`` objects.
        """
        body: Dict[str, Any] = {"budgetIds": budget_ids}
        data = await self._request(
            "POST", "/api/v1/budgets/cancel-batch", json=body, retryable=False
        )
        return [_parse_budget(b) for b in data]

    # -----------------------------------------------------------------------
    # Resource budget convenience methods
    # -----------------------------------------------------------------------

    async def create_token_budget(
        self,
        *,
        model: str,
        max_tokens: int,
        expires_at: Optional[str] = None,
        expires_in: Optional[Union[str, int, timedelta]] = None,
        intent_context: Optional[str] = None,
        user_id: str = "agent",
        external_reference: Optional[str] = None,
        allocations: Optional[List[Dict[str, Any]]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Budget:
        """
        Create a resource budget for tracking token usage.

        Anomaly detection is disabled for token budgets — thresholds calibrated
        for dollar amounts produce false positives on token counts.

        :param model:      LLM model identifier stored in metadata (e.g. ``"gpt-4o"``).
        :param max_tokens: Total token cap for the budget.
        :param expires_at: Absolute ISO 8601 expiry. Mutually exclusive with ``expires_in``.
        :param expires_in: Relative duration (e.g. ``"2h"``, ``"30m"``).
        """
        meta = dict(metadata or {})
        meta.setdefault("model", model)
        return await self.create_budget(
            user_id=user_id,
            total_limit=float(max_tokens),
            expires_at=expires_at,
            expires_in=expires_in,
            unit="tokens",
            intent_context=intent_context,
            external_reference=external_reference,
            anomaly_detection_enabled=False,
            allocations=allocations,
            metadata=meta,
        )

    async def authorize_tokens(
        self,
        *,
        session_token: str,
        agent_id: str,
        estimated_tokens: int,
        model: str,
        idempotency_key: Optional[str] = None,
        description: Optional[str] = None,
        claimed_category: Optional[str] = None,
        trace_id: Optional[str] = None,
        dry_run: bool = False,
        **kwargs: Any,
    ) -> AuthorizationResult:
        """
        Pre-flight authorization for a token budget.

        Convenience wrapper around :meth:`authorize` for resource (token) budgets.
        Omits ``currency`` so the currency-mismatch check is skipped server-side.

        :param estimated_tokens: Upper-bound estimate of tokens this action will consume.
            After the LLM call completes, call :meth:`confirm_tokens` with the actual count.
        :param model: Stored in description for audit (e.g. ``"gpt-4o"``).
        """
        desc = description or f"LLM call via {model} — estimated {estimated_tokens:,} tokens"
        return await self.authorize(
            session_token=session_token,
            agent_id=agent_id,
            action_type="LLM_CALL",
            description=desc,
            requested_quantity=float(estimated_tokens),
            idempotency_key=idempotency_key,
            claimed_category=claimed_category,
            trace_id=trace_id,
            dry_run=dry_run,
            **kwargs,
        )

    async def confirm_tokens(
        self,
        event_id: str,
        actual_tokens: int,
        external_transaction_id: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Confirm a token authorization with the actual token count.

        :param actual_tokens: Real token count from the LLM response
            (e.g. ``response.usage.total_tokens``). May be less than estimated.
        """
        return await self.confirm_event(
            event_id=event_id,
            confirmed_quantity=float(actual_tokens),
            external_transaction_id=external_transaction_id,
        )

    # -------------------------------------------------------------------------
    # Replay
    # -------------------------------------------------------------------------

    async def replay_budget(
        self,
        budget_id: str,
        *,
        from_time=None,
        until=None,
        include_denied: bool = True,
        include_state_snapshots: bool = True,
        page_size: int = 100,
        page_token: str | None = None,
    ) -> dict:
        """
        Replay all events for a budget in chronological order.

        Returns each event with the projected budget state after it applied.
        Pure read — does not affect any budget state.
        """
        params = {
            "includeDenied": str(include_denied).lower(),
            "includeStateSnapshots": str(include_state_snapshots).lower(),
            "pageSize": min(page_size, 500),
        }
        if from_time is not None:
            params["from"] = from_time.isoformat() if hasattr(from_time, "isoformat") else str(from_time)
        if until is not None:
            params["until"] = until.isoformat() if hasattr(until, "isoformat") else str(until)
        if page_token:
            params["pageToken"] = page_token
        return await self._request("GET", f"/api/v1/budgets/{budget_id}/replay", params=params)

    async def get_budget_state_at(self, budget_id: str, at) -> dict:
        """Project the budget state to a specific point in time."""
        at_str = at.isoformat() if hasattr(at, "isoformat") else str(at)
        return await self._request(
            "GET", f"/api/v1/budgets/{budget_id}/replay/state", params={"at": at_str}
        )

    async def get_budget_timeline(
        self,
        budget_id: str,
        *,
        from_time=None,
        until=None,
    ) -> dict:
        """Return events in chronological order without state snapshots."""
        params = {}
        if from_time is not None:
            params["from"] = from_time.isoformat() if hasattr(from_time, "isoformat") else str(from_time)
        if until is not None:
            params["until"] = until.isoformat() if hasattr(until, "isoformat") else str(until)
        return await self._request("GET", f"/api/v1/budgets/{budget_id}/replay/timeline", params=params)

    async def replay_counterfactual(
        self,
        budget_id: str,
        *,
        hypothetical_policy: dict | None = None,
        manifest_version: str | None = None,
        from_time=None,
        until=None,
    ) -> dict:
        """
        Replay actual authorized events against a hypothetical policy.

        Provide exactly one of hypothetical_policy or manifest_version.
        """
        body: dict = {}
        if hypothetical_policy is not None:
            body["hypotheticalPolicy"] = hypothetical_policy
        if manifest_version is not None:
            body["manifestVersion"] = manifest_version
        if from_time is not None:
            body["from"] = from_time.isoformat() if hasattr(from_time, "isoformat") else str(from_time)
        if until is not None:
            body["until"] = until.isoformat() if hasattr(until, "isoformat") else str(until)
        return await self._request(
            "POST", f"/api/v1/budgets/{budget_id}/replay/counterfactual", json=body
        )

    async def rotate_session_token(self, budget_id: str) -> str:
        """
        Issue a new session token for the budget.

        The old token remains valid for a short grace period so in-flight
        agents finish cleanly.

        :returns: The new raw session token.
        """
        data = await self._request(
            "POST", f"/api/v1/budgets/{budget_id}/rotate-token", retryable=True
        )
        return str(data["sessionToken"])

    # -----------------------------------------------------------------------
    # Authorization
    # -----------------------------------------------------------------------

    async def authorize(
        self,
        session_token: str,
        agent_id: str,
        action_type: str,
        description: str,
        requested_quantity: float,
        idempotency_key: Optional[str] = None,
        currency: Optional[str] = None,
        intent_context: Optional[str] = None,
        entity_id: Optional[str] = None,
        claimed_category: Optional[str] = None,
        claimed_item_type: Optional[str] = None,
        parent_event_id: Optional[str] = None,
        agent_type: Optional[str] = None,
        trace_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        max_subtree_quantity: Optional[float] = None,
        dry_run: bool = False,
        **kwargs: Any,
    ) -> AuthorizationResult:
        """
        Pre-flight spend authorization.

        :param requested_quantity: The quantity to reserve. For monetary budgets this
            is the dollar amount; for resource budgets it's the token/call count.
        :param currency: Required for monetary budgets — must match the budget's currency.
            Omit or pass ``None`` for resource budgets (tokens, api_calls, etc.).
        :param idempotency_key: Optional. A unique key for this request so retries
            are safe and never double-spend. When omitted, a UUID v4 is generated
            automatically. Pass an explicit key when you need idempotency across
            retries (e.g. store it before the first attempt, reuse on retry).
        :param dry_run: When ``True``, all enforcement checks run and a full
            ``AUTHORIZED`` or ``DENIED`` result is returned, but nothing is written
            to the ledger and no webhooks fire. Use during integration testing.

        :returns: ``AuthorizationResult`` — check ``.is_authorized`` or call
            ``.raise_if_denied()`` for exception-driven flow.
        """
        if not idempotency_key or not idempotency_key.strip():
            idempotency_key = str(uuid.uuid4())

        # Instrumentation precedence: explicit > ambient ContextVar.
        # Framework callback inference sits between these and is resolved in the
        # integration layer before this call is made.
        effective_parent_id = parent_event_id or get_current_event_id()

        # Forward the active OTEL trace ID to the server for ledger correlation.
        effective_trace_id = trace_id or get_current_trace_id()

        body: Dict[str, Any] = {
            "agentId": agent_id,
            "actionType": action_type,
            "description": description,
            "requestedQuantity": requested_quantity,
            "idempotencyKey": idempotency_key,
        }
        if currency is not None:
            body["currency"] = currency
        if agent_type is not None:
            body["agentType"] = agent_type
        if intent_context is not None:
            body["intentContext"] = intent_context
        if entity_id is not None:
            body["entityId"] = entity_id
        if claimed_category is not None:
            body["claimedCategory"] = claimed_category
        if claimed_item_type is not None:
            body["claimedItemType"] = claimed_item_type
        if effective_parent_id is not None:
            body["parentEventId"] = effective_parent_id
        if effective_trace_id is not None:
            body["traceId"] = effective_trace_id
        if metadata is not None:
            body["metadata"] = metadata
        if max_subtree_quantity is not None:
            body["maxSubtreeQuantity"] = max_subtree_quantity
        if dry_run:
            body["dryRun"] = True

        token_prefix = session_token[:8] if len(session_token) >= 8 else "???"
        logger.debug(
            "authorize: agentId=%s quantity=%s key=%s token_prefix=%s parent=%s",
            agent_id, requested_quantity, idempotency_key, token_prefix,
            effective_parent_id,
        )

        extra_headers = {"X-Session-Token": session_token}
        with authorize_span(
            agent_id, action_type, requested_quantity,
            claimed_category, effective_parent_id, dry_run,
        ) as span:
            data = await self._request(
                "POST", "/api/v1/authorize",
                json=body, headers=extra_headers, retryable=True,
            )
            result = _parse_authorization_result(data)
            finish_authorize_span(span, result)

        # Propagate authorized event_id into ambient context for nested calls.
        if result.is_authorized and not dry_run:
            _set_current_event_id(result.event_id)

        return result

    # -----------------------------------------------------------------------
    # Payment lifecycle
    # -----------------------------------------------------------------------

    async def confirm_event(
        self,
        event_id: str,
        confirmed_quantity: float,
        external_transaction_id: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Confirm a previously authorized event.

        :param confirmed_quantity:      Actual quantity consumed (may differ from requested).
            Pass 0.0 if the action was a no-op.
        :param external_transaction_id: Reference from your payment processor for audit.
        """
        body: Dict[str, Any] = {"confirmedQuantity": confirmed_quantity}
        if external_transaction_id is not None:
            body["externalTransactionId"] = external_transaction_id

        with lifecycle_span("figuard.confirm", event_id) as span:
            span.set_attribute("figuard.confirmed_quantity", float(confirmed_quantity))
            data = await self._request(
                "POST", f"/api/v1/events/{event_id}/confirm", json=body, retryable=True
            )
        return _parse_spend_event(data)

    async def fail_event(
        self,
        event_id: str,
        reason: str,
        error_message: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Mark an authorized event as failed (e.g. payment processor declined).

        Releases the reserved funds back to the budget.
        """
        body: Dict[str, Any] = {"reason": reason}
        if error_message is not None:
            body["errorMessage"] = error_message

        with lifecycle_span("figuard.fail", event_id) as span:
            span.set_attribute("figuard.reason", reason)
            data = await self._request(
                "POST", f"/api/v1/events/{event_id}/fail", json=body, retryable=True
            )
        return _parse_spend_event(data)

    async def void_event(
        self,
        event_id: str,
        reason: str,
        void_child_events: bool = False,
    ) -> VoidResult:
        """
        Void an authorized event that was never confirmed.

        :param void_child_events: When True, also void any child events in the
            causal chain. Raises HTTP 409 if any child has an external
            transaction ID.
        """
        body: Dict[str, Any] = {
            "reason": reason,
            "voidChildEvents": void_child_events,
        }
        data = await self._request(
            "POST", f"/api/v1/events/{event_id}/void", json=body, retryable=True
        )
        return VoidResult(event=_parse_spend_event(data))

    async def void_tree(self, event_id: str, reason: str) -> VoidTreeResult:
        """
        Atomically void a root event and every ``AUTHORIZED`` descendant in its
        causal subtree — in a single server-side transaction.

        Use when an orchestration job is cancelled and you need to release all
        child agent reservations at once.
        """
        with void_tree_span(event_id, reason) as span:
            data = await self._request(
                "POST", f"/api/v1/events/{event_id}/void-tree",
                json={"reason": reason},
                retryable=True,
            )
            result = VoidTreeResult(
                root_event_id=data["rootEventId"],
                voided_count=data["voidedCount"],
                total_quantity_released=float(data["totalQuantityReleased"]),
                voided_event_ids=data["voidedEventIds"],
                reason=data["reason"],
                currency=data.get("currency"),
            )
            finish_void_tree_span(span, result)
        return result

    # -----------------------------------------------------------------------
    # Ledger & reporting
    # -----------------------------------------------------------------------

    async def get_ledger(
        self,
        budget_id: str,
        page: int = 0,
        size: int = 20,
        decision: Optional[str] = None,
        trace_id: Optional[str] = None,
    ) -> LedgerPage:
        """
        Paginated spend event ledger for a budget, newest first.

        :param decision: Optional filter — one of ``AUTHORIZED``, ``CONFIRMED``,
            ``DENIED``, ``VOIDED``, ``FAILED``.
        :param trace_id: Optional filter — return only events from a specific agent run.
        """
        params: Dict[str, Any] = {"page": page, "size": size}
        if decision is not None:
            params["decision"] = decision
        if trace_id is not None:
            params["traceId"] = trace_id

        data = await self._request(
            "GET", f"/api/v1/budgets/{budget_id}/ledger", params=params, retryable=True
        )
        events = [_parse_spend_event(e) for e in data.get("content", [])]
        return LedgerPage(
            events=events,
            total_elements=data.get("totalElements", 0),
            total_pages=data.get("totalPages", 0),
            page=data.get("number", page),
            size=data.get("size", size),
        )

    async def iter_events(
        self,
        budget_id: str,
        decision: Optional[str] = None,
        trace_id: Optional[str] = None,
        page_size: int = 100,
    ) -> AsyncIterator[SpendEventResponse]:
        """
        Async iterator over every spend event for a budget, paginating automatically.

        Yields events newest-first::

            async for event in client.iter_events(budget_id):
                await process(event)

            # Collect all confirmed events:
            confirmed = [e async for e in client.iter_events(budget_id, decision="CONFIRMED")]

        :param decision: Optional filter — ``AUTHORIZED``, ``CONFIRMED``,
            ``DENIED``, ``VOIDED``, or ``FAILED``.
        :param trace_id: Optional filter — return only events from a specific run.
        :param page_size: Events per page (default 100, max 500).
        """
        page = 0
        while True:
            ledger = await self.get_ledger(
                budget_id=budget_id,
                page=page,
                size=page_size,
                decision=decision,
                trace_id=trace_id,
            )
            for event in ledger.events:
                yield event
            if not ledger.has_next:
                break
            page += 1

    async def get_spend_tree(self, budget_id: str) -> SpendTree:
        """Hierarchical view of all spend events for a budget."""
        data = await self._request(
            "GET", f"/api/v1/budgets/{budget_id}/tree", retryable=True
        )
        roots = [_parse_tree_node(n) for n in data.get("roots", [])]
        return SpendTree(
            budget_id=budget_id,
            roots=roots,
            total_events=data.get("totalEvents", 0),
        )

    async def get_receipt_url(self, budget_id: str) -> str:
        """
        Get a shareable public receipt URL for a completed budget.

        The URL is valid for 7 days and requires no authentication.
        """
        data = await self._request(
            "GET", f"/api/v1/budgets/{budget_id}/receipt", retryable=True
        )
        return str(data["receiptUrl"])

    # -----------------------------------------------------------------------
    # Delegation tokens
    # -----------------------------------------------------------------------

    async def create_delegation_token(
        self,
        budget_id: str,
        label: str,
        caps: List[Dict[str, Any]],
    ) -> DelegationToken:
        """
        Create a scoped delegation token for a fleet budget.

        Each sub-agent in the fleet gets its own token with per-category spend
        caps. The sub-agent calls ``authorize`` with this token exactly as it
        would with a normal session token — FiGuard enforces both the per-token
        caps and the fleet-level allocations transparently.

        :param budget_id: The fleet budget ID to delegate from.
        :param label:     Human-readable label, e.g. ``"refund-agent-order-123"``.
        :param caps:      List of ``{"category": str, "limit": float}`` dicts.
                          Only the listed categories have per-token caps. Others
                          pass through to the fleet allocation only.

        :returns: ``DelegationToken`` with ``session_token`` populated — store
                  it securely and hand it to the sub-agent immediately. It is
                  never returned again.
        """
        body: Dict[str, Any] = {"label": label, "caps": caps}
        data = await self._request(
            "POST",
            f"/api/v1/budgets/{budget_id}/delegation-tokens",
            json=body,
            retryable=False,
        )
        return _parse_delegation_token(data)

    async def get_delegation_token(self, token_id: str) -> DelegationToken:
        """
        Get the current state of a delegation token.

        The raw session token is never returned — only the prefix.
        """
        data = await self._request(
            "GET", f"/api/v1/delegation-tokens/{token_id}", retryable=True
        )
        return _parse_delegation_token(data)

    async def list_delegation_tokens(self, budget_id: str) -> List[DelegationToken]:
        """List all delegation tokens for a fleet budget."""
        data = await self._request(
            "GET",
            f"/api/v1/budgets/{budget_id}/delegation-tokens",
            retryable=True,
        )
        return [_parse_delegation_token(t) for t in data]

    async def revoke_delegation_token(self, token_id: str) -> DelegationToken:
        """
        Revoke a delegation token immediately.

        Any subsequent ``authorize`` call using this token will be rejected with
        ``DELEGATION_TOKEN_REVOKED``. Already-authorized events are not affected.
        Idempotent — revoking an already-revoked token returns HTTP 200.
        """
        data = await self._request(
            "DELETE", f"/api/v1/delegation-tokens/{token_id}", retryable=False
        )
        return _parse_delegation_token(data)

    # -----------------------------------------------------------------------
    # Internal HTTP layer
    # -----------------------------------------------------------------------

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
        retryable: bool = False,
    ) -> Dict[str, Any]:
        """
        Execute an async HTTP request with optional retry logic.

        Retries on:
        - Connection errors / timeouts (``aiohttp.ClientError``)
        - 5xx responses

        Never retries 4xx — these are caller errors and won't change on retry.
        """
        url = f"{self._base_url}{path}"
        session = self._get_session()
        attempts = _MAX_RETRIES if retryable else 1
        last_exc: Optional[Exception] = None

        for attempt in range(attempts):
            if attempt > 0:
                delay = _RETRY_BACKOFF_BASE * (2 ** (attempt - 1))
                logger.debug(
                    "Retry %d/%d for %s %s in %.1fs", attempt, attempts - 1, method, path, delay
                )
                await asyncio.sleep(delay)

            try:
                async with session.request(
                    method,
                    url,
                    json=json,
                    params=params,
                    headers=headers,
                ) as resp:
                    if resp.status >= 500 and attempt < attempts - 1:
                        logger.warning(
                            "Server error %d on %s %s (attempt %d), will retry",
                            resp.status, method, path, attempt + 1,
                        )
                        last_exc = None
                        continue

                    return await _handle_async_response(resp)

            except aiohttp.ClientError as exc:
                last_exc = exc
                logger.warning(
                    "Connection error on %s %s (attempt %d): %s",
                    method, path, attempt + 1, exc,
                )
                continue

        if last_exc is not None:
            raise FiGuardConnectionError(
                f"All {attempts} attempts failed for {method} {path}: {last_exc}"
            ) from last_exc

        # All attempts were 5xx — make one final request to raise the error
        async with session.request(method, url, json=json, params=params, headers=headers) as resp:
            return await _handle_async_response(resp)


# ---------------------------------------------------------------------------
# Response parsing
# ---------------------------------------------------------------------------

async def _handle_async_response(resp: "aiohttp.ClientResponse") -> Dict[str, Any]:
    """Raise FiGuardApiError for non-2xx, otherwise return parsed JSON."""
    if resp.status >= 400:
        raw: Optional[dict] = None
        try:
            raw = await resp.json(content_type=None)
            message = (raw or {}).get("message") or (raw or {}).get("error") or await resp.text()
        except Exception:
            message = await resp.text()
        raise FiGuardApiError(status_code=resp.status, message=message, raw=raw)

    if resp.status == 204 or resp.content_length == 0:
        return {}

    return await resp.json(content_type=None)  # type: ignore[no-any-return]
