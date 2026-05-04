"""
FiGuard asynchronous Python client.

Usage::

    from figuard import AsyncFiGuardClient

    async with AsyncFiGuardClient(api_key="ab_live_...") as client:
        budget = await client.create_budget(
            user_id="user_123",
            total_limit=500.00,
            expires_at="2024-12-31T23:59:59Z",
        )

        result = await client.authorize(
            session_token=budget.session_token,
            agent_id="agent_flight_booker",
            action_type="PURCHASE",
            description="Book NYC flight",
            requested_amount=299.00,
            idempotency_key="txn-abc-001",
        )

        if result.is_authorized:
            await client.confirm_event(result.event_id, confirmed_amount=299.00)

Designed for frameworks that run on asyncio: LangChain, CrewAI, OpenAI Agents SDK,
FastAPI background tasks, etc.

Install the async extra::

    pip install figuard[async]
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Dict, List, Optional, Union

try:
    import aiohttp
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "AsyncFiGuardClient requires aiohttp. "
        "Install it with: pip install figuard[async]"
    ) from exc

from .exceptions import FiGuardApiError, FiGuardConnectionError
from .models import (
    AllocationResponse,
    AllocationSnapshot,
    AuthorizationResult,
    Budget,
    BudgetSnapshot,
    LedgerPage,
    SpendEventResponse,
    SpendTree,
    SpendTreeNode,
    VoidResult,
)
from .client import (
    _parse_authorization_result,
    _parse_budget,
    _parse_spend_event,
    _parse_tree_node,
)

logger = logging.getLogger(__name__)

_MAX_RETRIES = 3
_RETRY_BACKOFF_BASE = 1.0  # seconds; doubles each retry (1s, 2s, 4s)


class AsyncFiGuardClient:
    """
    Asynchronous FiGuard API client backed by ``aiohttp``.

    Supports ``async with`` for managed session lifecycle::

        async with AsyncFiGuardClient(api_key="ab_live_...") as client:
            budget = await client.create_budget(...)

    Or construct manually and call ``await client.close()`` when done::

        client = AsyncFiGuardClient(api_key="ab_live_...")
        try:
            budget = await client.create_budget(...)
        finally:
            await client.close()

    :param api_key:  Your ``ab_live_...`` or ``ab_test_...`` API key.
    :param base_url: Override for self-hosted deployments.
    :param timeout:  Per-request timeout in seconds (default 30).
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.figuard.io",
        timeout: int = 30,
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._default_headers: Dict[str, str] = {
            "X-Agent-Budget-Key": api_key,
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
        expires_at: str,
        currency: str = "USD",
        intent_context: Optional[str] = None,
        intent_tags: Optional[List[str]] = None,
        external_reference: Optional[str] = None,
        soft_limit: Optional[float] = None,
        max_transaction_amount: Optional[float] = None,
        anomaly_detection_enabled: bool = False,
        allocations: Optional[List[Dict[str, Any]]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Budget:
        """
        Create a new agent budget.

        :returns: ``Budget`` with ``session_token`` populated — store this
                  securely; it is never returned again.
        """
        body: Dict[str, Any] = {
            "userId": user_id,
            "totalLimit": total_limit,
            "expiresAt": expires_at,
            "currency": currency,
        }
        if intent_context is not None:
            body["intentContext"] = intent_context
        if intent_tags is not None:
            body["intentTags"] = intent_tags
        if external_reference is not None:
            body["externalReference"] = external_reference
        if soft_limit is not None:
            body["softLimit"] = soft_limit
        if max_transaction_amount is not None:
            body["maxTransactionAmount"] = max_transaction_amount
        if anomaly_detection_enabled:
            body["anomalyDetectionEnabled"] = True
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
        return data["sessionToken"]

    # -----------------------------------------------------------------------
    # Authorization
    # -----------------------------------------------------------------------

    async def authorize(
        self,
        session_token: str,
        agent_id: str,
        action_type: str,
        description: str,
        requested_amount: float,
        idempotency_key: Optional[str] = None,
        currency: str = "USD",
        intent_context: Optional[str] = None,
        entity_id: Optional[str] = None,
        claimed_category: Optional[str] = None,
        claimed_item_type: Optional[str] = None,
        parent_event_id: Optional[str] = None,
        agent_type: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> AuthorizationResult:
        """
        Pre-flight spend authorization.

        :param idempotency_key: **Required.** A unique key for this request so
            retries are safe and never double-spend. Raises ``ValueError`` if
            omitted or blank.

        :returns: ``AuthorizationResult`` — check ``.is_authorized`` or call
            ``.raise_if_denied()`` for exception-driven flow.
        """
        if not idempotency_key or not idempotency_key.strip():
            raise ValueError(
                "idempotency_key is required for authorize(). "
                "Generate one per logical spend intent (e.g. uuid4()) and reuse it on retries."
            )

        body: Dict[str, Any] = {
            "agentId": agent_id,
            "actionType": action_type,
            "description": description,
            "requestedAmount": requested_amount,
            "currency": currency,
            "idempotencyKey": idempotency_key,
        }
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
        if parent_event_id is not None:
            body["parentEventId"] = parent_event_id
        if metadata is not None:
            body["metadata"] = metadata

        token_prefix = session_token[:8] if len(session_token) >= 8 else "???"
        logger.debug(
            "authorize: agentId=%s amount=%s key=%s token_prefix=%s",
            agent_id, requested_amount, idempotency_key, token_prefix,
        )

        extra_headers = {"X-Session-Token": session_token}
        data = await self._request(
            "POST", "/api/v1/authorize",
            json=body, headers=extra_headers, retryable=True,
        )
        return _parse_authorization_result(data)

    # -----------------------------------------------------------------------
    # Payment lifecycle
    # -----------------------------------------------------------------------

    async def confirm_event(
        self,
        event_id: str,
        confirmed_amount: float,
        external_transaction_id: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Confirm a previously authorized event.

        :param confirmed_amount:        Actual amount spent (may differ from requested).
        :param external_transaction_id: Reference from your payment processor for audit.
        """
        body: Dict[str, Any] = {"confirmedAmount": confirmed_amount}
        if external_transaction_id is not None:
            body["externalTransactionId"] = external_transaction_id

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

    # -----------------------------------------------------------------------
    # Ledger & reporting
    # -----------------------------------------------------------------------

    async def get_ledger(
        self,
        budget_id: str,
        page: int = 0,
        size: int = 20,
        decision: Optional[str] = None,
    ) -> LedgerPage:
        """
        Paginated spend event ledger for a budget, newest first.

        :param decision: Optional filter — one of ``AUTHORIZED``, ``CONFIRMED``,
            ``DENIED``, ``VOIDED``, ``FAILED``.
        """
        params: Dict[str, Any] = {"page": page, "size": size}
        if decision is not None:
            params["decision"] = decision

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
        return data["receiptUrl"]

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

    return await resp.json(content_type=None)
