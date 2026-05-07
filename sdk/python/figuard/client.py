"""
FiGuard synchronous Python client.

Usage::

    from figuard import FiGuardClient

    client = FiGuardClient(api_key="ab_live_...")

    budget = client.create_budget(
        user_id="user_123",
        total_limit=500.00,
        expires_at="2024-12-31T23:59:59Z",
    )

    result = client.authorize(
        session_token=budget.session_token,
        agent_id="agent_flight_booker",
        action_type="PURCHASE",
        description="Book NYC flight",
        requested_amount=299.00,
        idempotency_key="txn-abc-001",
    )

    if result.is_authorized:
        # ... execute the transaction ...
        client.confirm_event(result.event_id, confirmed_amount=299.00)
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional, Union

import requests
from requests import Response

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

logger = logging.getLogger(__name__)

# Retry configuration
_MAX_RETRIES = 3
_RETRY_BACKOFF_BASE = 1.0  # seconds; doubles each retry (1s, 2s, 4s)


class FiGuardClient:
    """
    Synchronous FiGuard API client.

    Thread-safe: the underlying ``requests.Session`` is used for connection
    pooling only; no mutable state is shared between calls.

    :param api_key:  Your ``ab_live_...`` or ``ab_test_...`` API key.
    :param base_url: Override for self-hosted deployments.
    :param timeout:  Per-request timeout in seconds (default 30).
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = "http://localhost:8080",
        timeout: int = 30,
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        self._session = requests.Session()
        self._session.headers.update(
            {
                "X-Agent-Budget-Key": api_key,
                "Content-Type": "application/json",
                "Accept": "application/json",
            }
        )

    # -----------------------------------------------------------------------
    # Budget management
    # -----------------------------------------------------------------------

    def create_budget(
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

        data = self._request("POST", "/api/v1/budgets", json=body, retryable=True)
        return _parse_budget(data)

    def get_budget(self, budget_id: str) -> Budget:
        """Fetch the current state of a budget."""
        data = self._request("GET", f"/api/v1/budgets/{budget_id}", retryable=True)
        return _parse_budget(data)

    def resume_budget(
        self,
        budget_id: str,
        override_reason: str,
        override_by: Optional[str] = None,
    ) -> Budget:
        """
        Resume a PAUSED budget.

        :param override_reason: Required human-readable reason for the override.
        :param override_by:     Optional identifier for the operator or system performing the override.
        :raises FiGuardApiError: HTTP 409 if budget is not currently PAUSED.
        """
        body: Dict[str, Any] = {"overrideReason": override_reason}
        if override_by is not None:
            body["overrideBy"] = override_by
        data = self._request(
            "POST", f"/api/v1/budgets/{budget_id}/resume", json=body, retryable=True
        )
        return _parse_budget(data)

    def rotate_session_token(self, budget_id: str) -> str:
        """
        Issue a new session token for the budget.

        The old token remains valid for a short grace period so in-flight
        agents finish cleanly.

        :returns: The new raw session token.
        """
        data = self._request(
            "POST", f"/api/v1/budgets/{budget_id}/rotate-token", retryable=True
        )
        return str(data["sessionToken"])

    # -----------------------------------------------------------------------
    # Authorization
    # -----------------------------------------------------------------------

    def authorize(
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

        # Log only the prefix — the raw token must never appear in logs
        token_prefix = session_token[:8] if len(session_token) >= 8 else "???"
        logger.debug(
            "authorize: agentId=%s amount=%s key=%s token_prefix=%s",
            agent_id, requested_amount, idempotency_key, token_prefix,
        )

        headers = {"X-Session-Token": session_token}
        # authorize is retryable because idempotency_key guarantees idempotency
        data = self._request(
            "POST", "/api/v1/authorize", json=body, headers=headers, retryable=True
        )
        return _parse_authorization_result(data)

    # -----------------------------------------------------------------------
    # Payment lifecycle
    # -----------------------------------------------------------------------

    def confirm_event(
        self,
        event_id: str,
        confirmed_amount: float,
        external_transaction_id: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Confirm a previously authorized event.

        :param confirmed_amount:       Actual amount spent (may differ from requested).
        :param external_transaction_id: Reference from your payment processor for audit.
        """
        body: Dict[str, Any] = {"confirmedAmount": confirmed_amount}
        if external_transaction_id is not None:
            body["externalTransactionId"] = external_transaction_id

        data = self._request(
            "POST", f"/api/v1/events/{event_id}/confirm", json=body, retryable=True
        )
        return _parse_spend_event(data)

    def fail_event(
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

        data = self._request(
            "POST", f"/api/v1/events/{event_id}/fail", json=body, retryable=True
        )
        return _parse_spend_event(data)

    def void_event(
        self,
        event_id: str,
        reason: str,
        void_child_events: bool = False,
    ) -> VoidResult:
        """
        Void an authorized event that was never confirmed.

        :param void_child_events: When True, also void any child events in the
            causal chain. Raises HTTP 409 if any child has an external transaction ID.
        """
        body: Dict[str, Any] = {
            "reason": reason,
            "voidChildEvents": void_child_events,
        }
        data = self._request(
            "POST", f"/api/v1/events/{event_id}/void", json=body, retryable=True
        )
        return VoidResult(event=_parse_spend_event(data))

    # -----------------------------------------------------------------------
    # Ledger & reporting
    # -----------------------------------------------------------------------

    def get_ledger(
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

        data = self._request(
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

    def get_spend_tree(self, budget_id: str) -> SpendTree:
        """Hierarchical view of all spend events for a budget."""
        data = self._request(
            "GET", f"/api/v1/budgets/{budget_id}/tree", retryable=True
        )
        roots = [_parse_tree_node(n) for n in data.get("roots", [])]
        return SpendTree(
            budget_id=budget_id,
            roots=roots,
            total_events=data.get("totalEvents", 0),
        )

    def get_receipt_url(self, budget_id: str) -> str:
        """
        Get a shareable public receipt URL for a completed budget.

        The URL is valid for 7 days and requires no authentication.
        """
        data = self._request(
            "GET", f"/api/v1/budgets/{budget_id}/receipt", retryable=True
        )
        return str(data["receiptUrl"])

    # -----------------------------------------------------------------------
    # Internal HTTP layer
    # -----------------------------------------------------------------------

    def _request(
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
        Execute an HTTP request with optional retry logic.

        Retries on:
        - Connection errors / timeouts
        - 5xx responses

        Never retries 4xx (these are caller errors and won't change on retry).
        """
        url = f"{self._base_url}{path}"
        last_exc: Optional[Exception] = None
        attempts = _MAX_RETRIES if retryable else 1

        for attempt in range(attempts):
            if attempt > 0:
                delay = _RETRY_BACKOFF_BASE * (2 ** (attempt - 1))
                logger.debug("Retry %d/%d for %s %s in %.1fs", attempt, attempts - 1, method, path, delay)
                time.sleep(delay)

            try:
                resp: Response = self._session.request(
                    method,
                    url,
                    json=json,
                    params=params,
                    headers=headers,
                    timeout=self._timeout,
                )
            except requests.exceptions.RequestException as exc:
                last_exc = exc
                logger.warning("Connection error on %s %s (attempt %d): %s", method, path, attempt + 1, exc)
                continue

            if resp.status_code >= 500 and attempt < attempts - 1:
                logger.warning(
                    "Server error %d on %s %s (attempt %d), will retry",
                    resp.status_code, method, path, attempt + 1,
                )
                last_exc = None  # reset — we have a response, not a connection error
                continue

            return _handle_response(resp)

        if last_exc is not None:
            raise FiGuardConnectionError(
                f"All {attempts} attempts failed for {method} {path}: {last_exc}"
            ) from last_exc

        # last attempt was a 5xx — raise it
        return _handle_response(resp)


# ---------------------------------------------------------------------------
# Response parsing helpers
# ---------------------------------------------------------------------------

def _handle_response(resp: Response) -> Dict[str, Any]:
    """Raise FiGuardApiError for non-2xx, otherwise return parsed JSON."""
    if resp.status_code >= 400:
        raw: Optional[dict] = None
        message = resp.text
        try:
            raw = resp.json()
            message = raw.get("message") or raw.get("error") or resp.text
        except Exception:
            pass
        raise FiGuardApiError(status_code=resp.status_code, message=message, raw=raw)

    if resp.status_code == 204 or not resp.content:
        return {}

    return resp.json()  # type: ignore[no-any-return]


def _parse_budget(data: Dict[str, Any]) -> Budget:
    allocations = [
        AllocationResponse(
            id=a["id"],
            category=a["category"],
            allowed_categories=a.get("allowedCategories", []),
            limit=a["limit"],
            amount_spent=a["amountSpent"],
            amount_reserved=a["amountReserved"],
            available_amount=a["availableAmount"],
            status=a["status"],
            enforcement_mode=a.get("enforcementMode", "CATEGORY_CONSTRAINED"),
            forbidden_item_types=a.get("forbiddenItemTypes"),
        )
        for a in data.get("allocations", [])
    ]
    return Budget(
        id=data["id"],
        user_id=data["userId"],
        total_limit=data["totalLimit"],
        currency=data["currency"],
        amount_spent=data["amountSpent"],
        amount_reserved=data["amountReserved"],
        available_amount=data["availableAmount"],
        status=data["status"],
        expires_at=data["expiresAt"],
        created_at=data.get("createdAt"),
        session_token_prefix=data["sessionTokenPrefix"],
        intent_context=data.get("intentContext"),
        intent_tags=data.get("intentTags"),
        external_reference=data.get("externalReference"),
        soft_limit=data.get("softLimit"),
        max_transaction_amount=data.get("maxTransactionAmount"),
        allocations=allocations,
        cancelled_at=data.get("cancelledAt"),
        metadata=data.get("metadata"),
        session_token=data.get("sessionToken"),
    )


def _parse_authorization_result(data: Dict[str, Any]) -> AuthorizationResult:
    budget_snapshot: Optional[BudgetSnapshot] = None
    if snap := data.get("budgetSnapshot"):
        budget_snapshot = BudgetSnapshot(
            total_limit=snap["totalLimit"],
            amount_spent=snap["amountSpent"],
            amount_reserved=snap["amountReserved"],
            available_amount=snap["availableAmount"],
            status=snap["status"],
        )

    alloc_snapshot: Optional[AllocationSnapshot] = None
    if snap := data.get("allocationSnapshot"):
        alloc_snapshot = AllocationSnapshot(
            category=snap["category"],
            limit=snap["limit"],
            amount_spent=snap["amountSpent"],
            amount_reserved=snap["amountReserved"],
            available_amount=snap["availableAmount"],
            status=snap["status"],
        )

    return AuthorizationResult(
        event_id=data["eventId"],
        decision=data["decision"],
        budget_snapshot=budget_snapshot,
        allocation_snapshot=alloc_snapshot,
        approved_amount=data.get("approvedAmount"),
        authorized_at=data.get("authorizedAt"),
        denial_reason=_str(data.get("denialReason")),
        denial_message=data.get("denialMessage"),
        original_event_id=data.get("originalEventId"),
        original_event_status=data.get("originalEventStatus"),
    )


def _parse_spend_event(data: Dict[str, Any]) -> SpendEventResponse:
    return SpendEventResponse(
        id=data["id"],
        decision=data["decision"],
        requested_amount=data["requestedAmount"],
        currency=data["currency"],
        created_at=data["createdAt"],
        agent_id=data.get("agentId"),
        agent_type=data.get("agentType"),
        action_type=data.get("actionType"),
        description=data.get("description"),
        confirmed_amount=data.get("confirmedAmount"),
        entity_id=data.get("entityId"),
        claimed_category=data.get("claimedCategory"),
        claimed_item_type=data.get("claimedItemType"),
        intent_context=data.get("intentContext"),
        idempotency_key=data.get("idempotencyKey"),
        denial_reason=_str(data.get("denialReason")),
        failure_reason=data.get("failureReason"),
        parent_event_id=data.get("parentEventId"),
        metadata=data.get("metadata"),
    )


def _parse_tree_node(data: Dict[str, Any]) -> SpendTreeNode:
    # The server may return nodes as {"event": {...}, "children": [...]} (nested)
    # or as flat spend-event objects directly in the roots list (flat).
    if "event" in data:
        event = _parse_spend_event(data["event"])
        children = [_parse_tree_node(c) for c in data.get("children", [])]
    else:
        event = _parse_spend_event(data)
        children = [_parse_tree_node(c) for c in data.get("children", [])]
    return SpendTreeNode(event=event, children=children)


def _str(value: Any) -> Optional[str]:
    """Coerce an enum-like value (dict or string) to a plain string."""
    if value is None:
        return None
    if isinstance(value, str):
        return value
    # Spring may serialize enums as {"name": "...", "ordinal": N}
    if isinstance(value, dict):
        return value.get("name") or str(value)
    return str(value)
