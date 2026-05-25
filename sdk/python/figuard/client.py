"""
FiGuard synchronous Python client.

Usage::

    from figuard import FiGuardClient

    client = FiGuardClient(api_key="fg_live_...")

    budget = client.create_budget(
        user_id="user_123",
        total_limit=500.00,
        expires_at="2024-12-31T23:59:59Z",
    )

    result = client.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="agent_flight_booker",
        action_type="PURCHASE",
        description="Book NYC flight",
        requested_quantity=299.00,
        currency="USD",
        idempotency_key="txn-abc-001",
    )

    if result.is_authorized:
        # ... execute the transaction ...
        client.confirm_event(result.event_id, confirmed_quantity=299.00)
"""

from __future__ import annotations

import logging
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Union

import requests
from requests import Response

from .context import _set_current_event_id, get_current_event_id
from .exceptions import FiGuardApiError, FiGuardConnectionError
from .telemetry import (
    authorize_span,
    finish_authorize_span,
    lifecycle_span,
    void_tree_span,
    finish_void_tree_span,
    get_current_trace_id,
)
from .models import (
    AllocationResponse,
    AllocationSnapshot,
    ApiKey,
    AuthorizationResult,
    Budget,
    BudgetFundingResult,
    BudgetSnapshot,
    BudgetToken,
    DelegationToken,
    DelegationTokenAllocation,
    EntitlementItem,
    LedgerPage,
    SpendEventResponse,
    SpendTree,
    SpendTreeNode,
    Subscription,
    VoidResult,
    VoidTreeResult,
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

    :param api_key:  Your ``fg_live_...`` or ``fg_test_...`` API key.
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

        :param expires_at: Absolute ISO 8601 expiry timestamp (e.g. ``"2026-12-31T23:59:59Z"``).
            Mutually exclusive with ``expires_in``.
        :param expires_in: Relative duration from now. Accepts ``"24h"``, ``"7d"``, ``"30m"``,
            a ``timedelta``, or an integer number of seconds. Mutually exclusive with ``expires_at``.
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

    def extend_budget(
        self,
        budget_id: str,
        expires_at: Optional[str] = None,
        expires_in: Optional[Union[str, int, timedelta]] = None,
    ) -> Budget:
        """
        Extend a budget's expiry window.

        The new ``expires_at`` must be later than the current one and at most 24 hours
        from now (the same cap as creation — ``extend`` can be called repeatedly to keep
        a long-running agent alive).

        :param expires_at: Absolute ISO 8601 timestamp for the new expiry.
        :param expires_in: Relative duration from now (e.g. ``"2h"``, ``"30m"``).
            Mutually exclusive with ``expires_at``.
        :raises FiGuardApiError: HTTP 409 if budget is CANCELLED or EXHAUSTED.
        :raises FiGuardApiError: HTTP 400 if ``expires_at`` is before the current one.
        """
        body: Dict[str, Any] = {
            "expiresAt": _resolve_expires_at(expires_at, expires_in),
        }
        data = self._request(
            "POST", f"/api/v1/budgets/{budget_id}/extend", json=body, retryable=False
        )
        return _parse_budget(data)

    def cancel_batch(self, budget_ids: List[str]) -> List[Budget]:
        """
        Cancel up to 100 budgets in a single call.

        Already-terminal budgets (EXPIRED, CANCELLED, EXHAUSTED) are included in
        the response without raising an error — the call is idempotent per budget.

        :param budget_ids: List of budget IDs to cancel. Maximum 100.
        :raises FiGuardApiError: HTTP 400 if the list is empty or exceeds 100 items.
        :returns: List of updated ``Budget`` objects.
        """
        body: Dict[str, Any] = {"budgetIds": budget_ids}
        data = self._request(
            "POST", "/api/v1/budgets/cancel-batch", json=body, retryable=False
        )
        return [_parse_budget(b) for b in data]

    # -----------------------------------------------------------------------
    # Fund budget
    # -----------------------------------------------------------------------

    def fund_budget(
        self,
        budget_id: str,
        operation: str,
        amount: float,
        reason: Optional[str] = None,
    ) -> BudgetFundingResult:
        """
        Adjust a budget's totalLimit in-place.

        :param operation:
            - ``CREDIT``      — add *amount* to totalLimit.
            - ``DEBIT``       — subtract *amount* from totalLimit (rejected if result
              would drop below quantitySpent).
            - ``RESET``       — set totalLimit to exactly *amount*.
            - ``RESET_SPENT`` — zero quantitySpent and set totalLimit to *amount*;
              reactivates EXHAUSTED budgets.
        :param amount: Must be positive.
        :param reason: Optional note recorded for audit purposes.
        """
        body: Dict[str, Any] = {"operation": operation, "amount": amount}
        if reason is not None:
            body["reason"] = reason
        data = self._request("POST", f"/api/v1/budgets/{budget_id}/fund", json=body)
        return BudgetFundingResult(
            budget_id=data.get("budgetId", budget_id),
            operation=data["operation"],
            amount=float(data["amount"]),
            previous_total_limit=float(data["previousTotalLimit"]),
            total_limit=float(data["totalLimit"]),
            quantity_spent=float(data["quantitySpent"]),
            quantity_reserved=float(data["quantityReserved"]),
            available_quantity=float(data["availableQuantity"]),
            status=data["status"],
            reason=data.get("reason"),
            updated_at=data.get("updatedAt"),
            trace_id=data.get("traceId"),
        )

    # -----------------------------------------------------------------------
    # API keys
    # -----------------------------------------------------------------------

    def list_api_keys(self) -> List[ApiKey]:
        """List all API keys for this tenant. Raw key values are never returned here."""
        data = self._request("GET", "/api/v1/api-keys")
        return [_parse_api_key(k) for k in data]

    def create_api_key(self, description: Optional[str] = None) -> ApiKey:
        """
        Create a new API key. The ``raw_key`` field is populated **once** in the
        returned object — store it securely. All subsequent reads return ``None``.
        """
        body: Dict[str, Any] = {}
        if description is not None:
            body["description"] = description
        data = self._request("POST", "/api/v1/api-keys", json=body)
        return _parse_api_key(data)

    def revoke_api_key(self, key_id: str) -> ApiKey:
        """Revoke an API key. Idempotent. Row retained for audit."""
        data = self._request("POST", f"/api/v1/api-keys/{key_id}/revoke")
        return _parse_api_key(data)

    def rotate_api_key(self, key_id: str) -> ApiKey:
        """
        Revoke the current key and issue a replacement atomically.
        The new ``raw_key`` is returned once in the response.
        """
        data = self._request("POST", f"/api/v1/api-keys/{key_id}/rotate")
        return _parse_api_key(data)

    # -----------------------------------------------------------------------
    # Subscriptions & Entitlements
    # -----------------------------------------------------------------------

    def list_subscriptions(self) -> List[Subscription]:
        """List all subscriptions for this tenant."""
        data = self._request("GET", "/api/v1/subscriptions")
        return [_parse_subscription(s) for s in data]

    def create_subscription(
        self,
        external_subscriber_id: str,
        plan: str,
        renewal_period: str,
        starts_at: Optional[str] = None,
    ) -> Subscription:
        """
        Create a subscription.

        :param external_subscriber_id: Your user/customer ID.
        :param plan: Arbitrary plan label (e.g. ``"pro"``, ``"starter"``).
        :param renewal_period: ``MONTHLY`` | ``QUARTERLY`` | ``ANNUALLY``.
        :param starts_at: ISO 8601. Defaults to now.
        """
        body: Dict[str, Any] = {
            "externalSubscriberId": external_subscriber_id,
            "plan": plan,
            "renewalPeriod": renewal_period,
        }
        if starts_at is not None:
            body["startsAt"] = starts_at
        data = self._request("POST", "/api/v1/subscriptions", json=body)
        return _parse_subscription(data)

    def get_subscription(self, subscription_id: str) -> Subscription:
        """Get a subscription by its FiGuard ID."""
        data = self._request("GET", f"/api/v1/subscriptions/{subscription_id}")
        return _parse_subscription(data)

    def get_subscription_by_subscriber(self, external_subscriber_id: str) -> Subscription:
        """Look up a subscription by your own subscriber ID."""
        data = self._request(
            "GET", f"/api/v1/subscriptions/by-subscriber/{external_subscriber_id}"
        )
        return _parse_subscription(data)

    def pause_subscription(self, subscription_id: str) -> Subscription:
        """
        Pause a subscription. All linked budgets will receive HTTP 402
        (``SUBSCRIPTION_PAUSED``) on the next authorize call.
        """
        data = self._request("POST", f"/api/v1/subscriptions/{subscription_id}/pause")
        return _parse_subscription(data)

    def resume_subscription(self, subscription_id: str) -> Subscription:
        """Resume a paused subscription."""
        data = self._request("POST", f"/api/v1/subscriptions/{subscription_id}/resume")
        return _parse_subscription(data)

    def cancel_subscription(self, subscription_id: str) -> Subscription:
        """Cancel a subscription."""
        data = self._request("POST", f"/api/v1/subscriptions/{subscription_id}/cancel")
        return _parse_subscription(data)

    def list_entitlements(self, subscription_id: str) -> List[EntitlementItem]:
        """List all entitlement items for a subscription."""
        data = self._request("GET", f"/api/v1/subscriptions/{subscription_id}/entitlements")
        return [_parse_entitlement(e) for e in data]

    def add_entitlement(
        self,
        subscription_id: str,
        category: str,
        period_limit: float,
        overage_policy: str = "BLOCK",
        renewal_period: str = "MONTHLY",
        warn_at_percentage: Optional[float] = None,
    ) -> EntitlementItem:
        """
        Add an entitlement item to a subscription.

        :param category: Spend category this entitlement tracks (e.g. ``"api_calls"``).
        :param period_limit: Maximum quantity per renewal period.
        :param overage_policy: ``BLOCK`` (deny at limit) | ``WARN_ONLY`` (fire webhook but allow).
        :param renewal_period: ``MONTHLY`` | ``QUARTERLY`` | ``ANNUALLY``.
        :param warn_at_percentage: Fire ``ENTITLEMENT_STATE_CHANGED`` webhook when this
            percentage of the limit is consumed. E.g. ``80`` = warn at 80%.
        """
        body: Dict[str, Any] = {
            "category": category,
            "periodLimit": period_limit,
            "overagePolicy": overage_policy,
            "renewalPeriod": renewal_period,
        }
        if warn_at_percentage is not None:
            body["warnAtPercentage"] = warn_at_percentage
        data = self._request(
            "POST", f"/api/v1/subscriptions/{subscription_id}/entitlements", json=body
        )
        return _parse_entitlement(data)

    def get_entitlement(self, subscription_id: str, entitlement_item_id: str) -> EntitlementItem:
        """Get a single entitlement item including current consumption and state."""
        data = self._request(
            "GET",
            f"/api/v1/subscriptions/{subscription_id}/entitlements/{entitlement_item_id}",
        )
        return _parse_entitlement(data)

    def reset_entitlement(self, subscription_id: str, entitlement_item_id: str) -> EntitlementItem:
        """
        Manually reset an entitlement item's consumed counter to zero and advance
        ``nextRenewalAt``. Use for mid-period corrections or manual billing period control.
        """
        data = self._request(
            "POST",
            f"/api/v1/subscriptions/{subscription_id}/entitlements/{entitlement_item_id}/reset",
        )
        return _parse_entitlement(data)

    # -----------------------------------------------------------------------
    # Delegation tokens
    # -----------------------------------------------------------------------

    def create_delegation_token(
        self,
        budget_id: str,
        label: str,
        caps: List[Dict[str, Any]],
    ) -> DelegationToken:
        """
        Create a scoped delegation token for a fleet budget.

        Each sub-agent (e.g. per-customer refund agent) gets its own token with
        per-category spend caps. The sub-agent calls ``authorize()`` with this token
        exactly as it would with a normal session token — FiGuard resolves the parent
        budget and enforces both the per-token caps and the fleet-level allocations.

        :param budget_id: The fleet budget ID.
        :param label: Human-readable label, e.g. ``"refund-agent-order-123"``.
        :param caps: List of per-category caps::

            [
                {"category": "refund",     "limit": 3000},
                {"category": "llm_tokens", "limit": 10000},
            ]

        :returns: ``DelegationToken`` with ``session_token`` populated — hand it to the
                  sub-agent immediately; it is never returned again.
        """
        body: Dict[str, Any] = {"label": label, "caps": caps}
        data = self._request(
            "POST", f"/api/v1/budgets/{budget_id}/delegation-tokens",
            json=body, retryable=False
        )
        return _parse_delegation_token(data)

    def get_delegation_token(self, token_id: str) -> DelegationToken:
        """Get a delegation token by ID. The raw session token is never returned."""
        data = self._request(
            "GET", f"/api/v1/delegation-tokens/{token_id}", retryable=True
        )
        return _parse_delegation_token(data)

    def list_delegation_tokens(self, budget_id: str) -> List[DelegationToken]:
        """List all delegation tokens for a fleet budget."""
        data = self._request(
            "GET", f"/api/v1/budgets/{budget_id}/delegation-tokens", retryable=True
        )
        return [_parse_delegation_token(t) for t in data]

    def revoke_delegation_token(self, token_id: str) -> DelegationToken:
        """
        Revoke a delegation token immediately.

        Any subsequent authorize call using this token returns INVALID_SESSION_TOKEN.
        Already-authorized events are not affected. Idempotent.

        Fires ``DELEGATION_TOKEN_REVOKED`` webhook.
        """
        data = self._request(
            "DELETE", f"/api/v1/delegation-tokens/{token_id}", retryable=False
        )
        return _parse_delegation_token(data)

    # -----------------------------------------------------------------------
    # Resource budget convenience methods
    # -----------------------------------------------------------------------

    def create_token_budget(
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

        Example::

            budget = client.create_token_budget(
                model="gpt-4o",
                max_tokens=50_000,
                expires_in="2h",
            )
        """
        meta = dict(metadata or {})
        meta.setdefault("model", model)
        return self.create_budget(
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

    def authorize_tokens(
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

        Example::

            auth = client.authorize_tokens(
                session_token=budget.primary_token.session_token,
                agent_id="summarizer",
                estimated_tokens=4_000,
                model="gpt-4o",
                idempotency_key="run-abc-step-1",
            )
            if auth.is_authorized:
                response = openai_client.chat(...)
                client.confirm_tokens(auth.event_id, actual_tokens=response.usage.total_tokens)
        """
        desc = description or f"LLM call via {model} — estimated {estimated_tokens:,} tokens"
        return self.authorize(
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

    def confirm_tokens(
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
        return self.confirm_event(
            event_id=event_id,
            confirmed_quantity=float(actual_tokens),
            external_transaction_id=external_transaction_id,
        )

    # -------------------------------------------------------------------------
    # Replay
    # -------------------------------------------------------------------------

    def replay_budget(
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

        :param budget_id: The budget to replay.
        :param from_time: Start of replay window (default: budget creation time).
        :param until: End of replay window (default: now).
        :param include_denied: Include DENIED events in sequence.
        :param include_state_snapshots: Include full budget state after each event.
        :param page_size: Events per page, max 500.
        :param page_token: Opaque cursor from a previous response for pagination.
        :returns: Dict with initial_state, events list, final_state, summary.
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
        return self._request("GET", f"/api/v1/budgets/{budget_id}/replay", params=params)

    def get_budget_state_at(self, budget_id: str, at) -> dict:
        """
        Project the budget state to a specific point in time.

        :param at: A datetime or ISO 8601 string.
        :returns: Dict with projected_at, events_applied, and state snapshot.
        """
        at_str = at.isoformat() if hasattr(at, "isoformat") else str(at)
        return self._request(
            "GET", f"/api/v1/budgets/{budget_id}/replay/state", params={"at": at_str}
        )

    def get_budget_timeline(
        self,
        budget_id: str,
        *,
        from_time=None,
        until=None,
    ) -> dict:
        """
        Return events in chronological order without state snapshots.

        Lighter than replay_budget — use when you need the sequence and
        timing but not the projected state at each step.
        """
        params = {}
        if from_time is not None:
            params["from"] = from_time.isoformat() if hasattr(from_time, "isoformat") else str(from_time)
        if until is not None:
            params["until"] = until.isoformat() if hasattr(until, "isoformat") else str(until)
        return self._request("GET", f"/api/v1/budgets/{budget_id}/replay/timeline", params=params)

    def replay_counterfactual(
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

        Answers: "If I had configured the budget differently, how many
        transactions would have been denied?"

        Provide exactly one of hypothetical_policy or manifest_version.

        :param hypothetical_policy: Dict with keys: total_limit, allocations,
            max_transaction_quantity, anomaly_detection_enabled.
        :param manifest_version: Saved manifest version ID (V1-post Priority 2).
        :returns: Dict with actual_policy_summary, hypothetical_policy_summary,
            and delta_events list.
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
        return self._request("POST", f"/api/v1/budgets/{budget_id}/replay/counterfactual", json=body)

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

        # Instrumentation precedence (highest → lowest):
        #   1. Explicit parent_event_id kwarg — caller declared it
        #   2. Ambient ContextVar — set by a prior authorize() in this execution context
        # Framework callback inference (LangChain run_id mapping) sits between these two
        # and is handled in the integration layer before this call.
        effective_parent_id = parent_event_id or get_current_event_id()

        # Forward the active OTEL trace ID to the server so ledger entries can be
        # correlated to the originating distributed trace. Caller-supplied trace_id
        # takes precedence; OTEL is used only as a fallback.
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

        # Log only the prefix — the raw token must never appear in logs
        token_prefix = session_token[:8] if len(session_token) >= 8 else "???"
        logger.debug(
            "authorize: agentId=%s quantity=%s key=%s token_prefix=%s parent=%s",
            agent_id, requested_quantity, idempotency_key, token_prefix,
            effective_parent_id,
        )

        headers = {"X-Session-Token": session_token}
        with authorize_span(
            agent_id, action_type, requested_quantity,
            claimed_category, effective_parent_id, dry_run,
        ) as span:
            # authorize is retryable because idempotency_key guarantees idempotency
            data = self._request(
                "POST", "/api/v1/authorize", json=body, headers=headers, retryable=True
            )
            result = _parse_authorization_result(data)
            finish_authorize_span(span, result)

        # Propagate event_id into ambient context so nested authorize() calls
        # automatically receive this as their parent_event_id without any
        # parameter threading. Only set on AUTHORIZED — denied events don't
        # become parents.
        if result.is_authorized and not dry_run:
            _set_current_event_id(result.event_id)

        return result

    # -----------------------------------------------------------------------
    # Payment lifecycle
    # -----------------------------------------------------------------------

    def confirm_event(
        self,
        event_id: str,
        confirmed_quantity: float,
        external_transaction_id: Optional[str] = None,
    ) -> SpendEventResponse:
        """
        Confirm a previously authorized event.

        :param confirmed_quantity:      Actual quantity consumed (may differ from requested).
            Pass 0.0 if the action was a no-op (e.g. a tool call that returned early).
        :param external_transaction_id: Reference from your payment processor for audit.
        """
        body: Dict[str, Any] = {"confirmedQuantity": confirmed_quantity}
        if external_transaction_id is not None:
            body["externalTransactionId"] = external_transaction_id

        with lifecycle_span("figuard.confirm", event_id) as span:
            span.set_attribute("figuard.confirmed_quantity", float(confirmed_quantity))
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

        with lifecycle_span("figuard.fail", event_id) as span:
            span.set_attribute("figuard.reason", reason)
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

    def void_tree(self, event_id: str, reason: str) -> VoidTreeResult:
        """
        Atomically void a root event and every ``AUTHORIZED`` descendant in its
        causal subtree — in a single server-side transaction.

        Use this when an orchestration job is cancelled and you want to release
        all child agent reservations at once instead of voiding each individually.

        ``CONFIRMED`` and already-``VOIDED`` descendants are left untouched.
        Raises HTTP 409 if any descendant has an ``externalTransactionId`` set
        (that event must be refunded before the tree can be voided).

        :param event_id: Root event ID — the orchestrator's authorization.
        :param reason:   Reason code written to every voided event's audit log.
        :returns: ``VoidTreeResult`` with total events voided and quantity released.
        """
        with void_tree_span(event_id, reason) as span:
            data = self._request(
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

    def get_ledger(
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
# Allocation builder helper
# ---------------------------------------------------------------------------

def build_allocations_from_percentages(
    total: float,
    percentages: Dict[str, float],
    enforcement_mode: Optional[str] = None,
    allowed_categories: Optional[Dict[str, List[str]]] = None,
    forbidden_item_types: Optional[Dict[str, List[str]]] = None,
) -> List[Dict[str, Any]]:
    """
    Build an allocations list from a total and a ``category → percentage`` mapping.

    The last bucket absorbs the floating-point remainder so limits always sum to
    exactly ``total``, avoiding ``$333.33 × 3 ≠ $1000.00`` precision errors.

    :param total:               Total budget limit (same unit as the budget's totalLimit).
    :param percentages:         ``{"flight": 60, "hotel": 30, "ground": 10}`` — must sum to 100.
    :param enforcement_mode:    Optional enforcement mode applied to every allocation
                                (``"OPEN"``, ``"CATEGORY_CONSTRAINED"``, ``"STRICT"``).
    :param allowed_categories:  Per-category override: ``{"flight": ["flight", "airline"]}``.
                                Required per-allocation when enforcement_mode is
                                ``CATEGORY_CONSTRAINED`` or ``STRICT``.
    :param forbidden_item_types: Per-category: ``{"flight": ["gift_card", "upgrade"]}``.
                                 Only evaluated in ``STRICT`` mode.

    :raises ValueError: If percentages do not sum to 100 (within 0.001 tolerance).

    Example::

        allocations = build_allocations_from_percentages(
            total=1000.00,
            percentages={"flight": 60, "hotel": 30, "ground": 10},
        )
        # → [{"category": "flight",  "limit": 600.0},
        #    {"category": "hotel",   "limit": 300.0},
        #    {"category": "ground",  "limit": 100.0}]  ← last bucket absorbs rounding
    """
    pct_sum = sum(percentages.values())
    if abs(pct_sum - 100) > 0.001:
        raise ValueError(
            f"Percentages must sum to 100, got {pct_sum:.4f}. "
            "Adjust your values so they add up to exactly 100."
        )

    categories = list(percentages.keys())
    result: List[Dict[str, Any]] = []
    assigned = 0.0

    for i, category in enumerate(categories):
        if i < len(categories) - 1:
            limit = round(percentages[category] / 100.0 * total, 4)
            assigned += limit
        else:
            # Last bucket: assign the remainder to absorb floating-point drift
            limit = round(total - assigned, 4)

        alloc: Dict[str, Any] = {"category": category, "limit": limit}
        if enforcement_mode is not None:
            alloc["enforcementMode"] = enforcement_mode
        if allowed_categories and category in allowed_categories:
            alloc["allowedCategories"] = allowed_categories[category]
        if forbidden_item_types and category in forbidden_item_types:
            alloc["forbiddenItemTypes"] = forbidden_item_types[category]
        result.append(alloc)

    return result


# ---------------------------------------------------------------------------
# expires_in resolver
# ---------------------------------------------------------------------------

def _resolve_expires_at(
    expires_at: Optional[str],
    expires_in: Optional[Union[str, int, timedelta]],
) -> str:
    """
    Resolve ``expires_at`` or ``expires_in`` to an absolute ISO 8601 timestamp.

    Accepted ``expires_in`` formats:
    - ``"24h"`` / ``"2h"`` / ``"30m"`` — hours or minutes suffix
    - ``"7d"`` / ``"30d"``             — days suffix
    - ``int``                           — seconds from now
    - ``timedelta``                     — added directly to now
    """
    if expires_at is not None and expires_in is not None:
        raise ValueError("Pass either expires_at or expires_in, not both.")
    if expires_at is not None:
        return expires_at
    if expires_in is None:
        raise ValueError("Either expires_at or expires_in is required.")
    if isinstance(expires_in, timedelta):
        dt = datetime.now(timezone.utc) + expires_in
    elif isinstance(expires_in, int):
        dt = datetime.now(timezone.utc) + timedelta(seconds=expires_in)
    elif isinstance(expires_in, str):
        m = re.match(r"^(\d+)([hmd])$", expires_in.strip())
        if not m:
            raise ValueError(
                f"Invalid expires_in: '{expires_in}'. "
                "Use '24h', '7d', '30m', an int (seconds), or a timedelta."
            )
        n, unit = int(m.group(1)), m.group(2)
        if unit == "h":
            dt = datetime.now(timezone.utc) + timedelta(hours=n)
        elif unit == "d":
            dt = datetime.now(timezone.utc) + timedelta(days=n)
        else:  # m
            dt = datetime.now(timezone.utc) + timedelta(minutes=n)
    else:
        raise TypeError(f"expires_in must be str, int, or timedelta, got {type(expires_in)}")
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


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
            quantity_spent=a["quantitySpent"],
            quantity_reserved=a["quantityReserved"],
            available_quantity=a["availableQuantity"],
            status=a["status"],
            enforcement_mode=a.get("enforcementMode", "CATEGORY_CONSTRAINED"),
            forbidden_item_types=a.get("forbiddenItemTypes"),
        )
        for a in data.get("allocations", [])
    ]
    raw_tokens = data.get("tokens")
    if raw_tokens is not None:
        tokens = [
            BudgetToken(
                category=t["category"],
                session_token=t.get("sessionToken"),
                session_token_prefix=t.get("sessionTokenPrefix"),
                unit=t.get("unit"),
                currency=t.get("currency"),
            )
            for t in raw_tokens
        ]
    elif data.get("sessionToken"):
        # Backward compatibility: servers prior to the tokens[] restructure returned
        # a flat sessionToken field at the budget root. Wrap it in a synthetic token
        # so primary_token works regardless of server version.
        tokens = [
            BudgetToken(
                category="default",
                session_token=data["sessionToken"],
                session_token_prefix=data.get("sessionTokenPrefix"),
                currency=data.get("currency"),
            )
        ]
    else:
        tokens = None
    return Budget(
        id=data["id"],
        user_id=data["userId"],
        total_limit=data["totalLimit"],
        currency=data.get("currency"),
        unit=data.get("unit"),
        quantity_spent=data["quantitySpent"],
        quantity_reserved=data["quantityReserved"],
        available_quantity=data["availableQuantity"],
        status=data["status"],
        expires_at=data["expiresAt"],
        created_at=data.get("createdAt"),
        intent_context=data.get("intentContext"),
        intent_tags=data.get("intentTags"),
        external_reference=data.get("externalReference"),
        soft_limit=data.get("softLimit"),
        max_transaction_quantity=data.get("maxTransactionQuantity"),
        authorization_expiry_seconds=data.get("authorizationExpirySeconds"),
        velocity_max_per_minute=data.get("velocityMaxPerMinute"),
        velocity_max_amount_per_hour=data.get("velocityMaxAmountPerHour"),
        velocity_max_per_day=data.get("velocityMaxPerDay"),
        allocations=allocations,
        cancelled_at=data.get("cancelledAt"),
        metadata=data.get("metadata"),
        tokens=tokens,
    )


def _parse_authorization_result(data: Dict[str, Any]) -> AuthorizationResult:
    budget_snapshot: Optional[BudgetSnapshot] = None
    if snap := data.get("budgetSnapshot"):
        budget_snapshot = BudgetSnapshot(
            total_limit=snap["totalLimit"],
            quantity_spent=snap["quantitySpent"],
            quantity_reserved=snap["quantityReserved"],
            available_quantity=snap["availableQuantity"],
            status=snap["status"],
        )

    alloc_snapshot: Optional[AllocationSnapshot] = None
    if snap := data.get("allocationSnapshot"):
        alloc_snapshot = AllocationSnapshot(
            category=snap["category"],
            limit=snap["limit"],
            quantity_spent=snap["quantitySpent"],
            quantity_reserved=snap["quantityReserved"],
            available_quantity=snap["availableQuantity"],
            status=snap["status"],
        )

    return AuthorizationResult(
        event_id=data["eventId"],
        decision=data["decision"],
        budget_snapshot=budget_snapshot,
        allocation_snapshot=alloc_snapshot,
        approved_quantity=data.get("approvedQuantity"),
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
        requested_quantity=data["requestedQuantity"],
        currency=data.get("currency"),
        created_at=data["createdAt"],
        agent_id=data.get("agentId"),
        agent_type=data.get("agentType"),
        action_type=data.get("actionType"),
        description=data.get("description"),
        confirmed_quantity=data.get("confirmedQuantity"),
        entity_id=data.get("entityId"),
        claimed_category=data.get("claimedCategory"),
        claimed_item_type=data.get("claimedItemType"),
        intent_context=data.get("intentContext"),
        idempotency_key=data.get("idempotencyKey"),
        denial_reason=_str(data.get("denialReason")),
        failure_reason=data.get("failureReason"),
        parent_event_id=data.get("parentEventId"),
        trace_id=data.get("traceId"),
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


def _parse_delegation_token(data: Dict[str, Any]) -> DelegationToken:
    caps = [
        DelegationTokenAllocation(
            id=c["id"],
            category=c["category"],
            total_limit=c["totalLimit"],
            quantity_spent=c["quantitySpent"],
            quantity_reserved=c["quantityReserved"],
            available_quantity=c["availableQuantity"],
        )
        for c in data.get("caps", [])
    ]
    return DelegationToken(
        id=data["id"],
        parent_budget_id=str(data["parentBudgetId"]),
        label=data["label"],
        status=data["status"],
        session_token_prefix=data["sessionTokenPrefix"],
        caps=caps,
        session_token=data.get("sessionToken"),
        revoked_at=data.get("revokedAt"),
        created_at=data.get("createdAt"),
    )


def _parse_api_key(data: Dict[str, Any]) -> ApiKey:
    return ApiKey(
        id=data["id"],
        key_prefix=data["keyPrefix"],
        active=data["active"],
        description=data.get("description"),
        created_at=data.get("createdAt"),
        last_used_at=data.get("lastUsedAt"),
        raw_key=data.get("rawKey"),
    )


def _parse_entitlement(data: Dict[str, Any]) -> EntitlementItem:
    return EntitlementItem(
        id=data["id"],
        category=data["category"],
        period_limit=float(data["periodLimit"]),
        overage_policy=data["overagePolicy"],
        renewal_period=data["renewalPeriod"],
        current_period_consumed=float(data.get("currentPeriodConsumed", 0)),
        current_period_reserved=float(data.get("currentPeriodReserved", 0)),
        state=data.get("state", "NORMAL"),
        warn_at_percentage=data.get("warnAtPercentage"),
        next_renewal_at=data.get("nextRenewalAt"),
        created_at=data.get("createdAt"),
    )


def _parse_subscription(data: Dict[str, Any]) -> Subscription:
    entitlements = [_parse_entitlement(e) for e in data.get("entitlements", [])]
    return Subscription(
        id=data["id"],
        external_subscriber_id=data["externalSubscriberId"],
        plan=data["plan"],
        status=data["status"],
        renewal_period=data["renewalPeriod"],
        entitlements=entitlements,
        starts_at=data.get("startsAt"),
        created_at=data.get("createdAt"),
        updated_at=data.get("updatedAt"),
    )


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
