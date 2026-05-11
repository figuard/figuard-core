"""
CompositeGuard — multi-resource authorization for agents that consume more than
one type of bounded resource per operation (e.g. tokens + USD).

Usage::

    from figuard import FiGuardClient
    from figuard.composite import CompositeGuard, GuardedResource

    guard = CompositeGuard([
        GuardedResource(client=client, session_token=token_a, resource="tokens"),
        GuardedResource(client=client, session_token=token_b, resource="USD"),
    ])

    result = guard.authorize(
        agent_id="travel_agent",
        action_type="LLM_CALL",
        description="search flights",
        requested={"tokens": 1500, "USD": 0.09},
        idempotency_key=str(uuid4()),
    )

    if result.all_authorized:
        # ... do the work ...
        guard.confirm(result, confirmed={"tokens": 1423, "USD": 0.085})
    else:
        print(f"Denied on {result.first_denial_resource}: {result.first_denial.denial_reason}")

Design notes:
- Resources are authorized in list order; first denial voids all prior authorizations.
- Idempotency key is namespaced per resource: "{key}:{resource}" — safe to retry.
- Confirm failures are swallowed (action already succeeded); logged at WARNING.
- Void failures on partial denial are logged at WARNING (auto-expiry will clean up).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from uuid import uuid4

from .client import FiGuardClient
from .models import AuthorizationResult, SpendEventResponse

logger = logging.getLogger(__name__)


@dataclass
class GuardedResource:
    """Pairs a FiGuardClient + session_token with a resource label."""
    client: FiGuardClient
    session_token: str
    resource: str  # e.g. "USD", "tokens", "api_calls" — used for routing and logging


@dataclass
class CompositeAuthorizationResult:
    """
    Result of a CompositeGuard.authorize() call.

    ``all_authorized`` is True only when every resource was authorized.
    ``event_ids()`` returns the list of authorized event IDs to pass to confirm/fail.
    """
    resources: List[str]
    authorizations: List[AuthorizationResult]
    all_authorized: bool
    first_denial: Optional[AuthorizationResult] = None
    first_denial_resource: Optional[str] = None

    def event_ids(self) -> List[str]:
        """IDs of all authorized events — pass to confirm() or fail()."""
        return [r.event_id for r in self.authorizations if r.is_authorized]


class CompositeGuard:
    """
    Synchronous multi-resource guard.

    Authorizes across all registered resources in order. If any resource denies,
    all previously authorized resources in this call are voided automatically.
    """

    def __init__(self, resources: List[GuardedResource]) -> None:
        if not resources:
            raise ValueError("CompositeGuard requires at least one GuardedResource")
        self._resources = resources

    def authorize(
        self,
        agent_id: str,
        action_type: str,
        description: str,
        requested: Dict[str, float],
        idempotency_key: Optional[str] = None,
        trace_id: Optional[str] = None,
        **kwargs: Any,
    ) -> CompositeAuthorizationResult:
        """
        Authorize across all resources.

        :param requested: mapping of resource name → quantity,
                          e.g. ``{"tokens": 1500, "USD": 0.09}``.
                          Resources not present in this dict default to 0.0.
        :param idempotency_key: shared key; namespaced per resource internally.
        :param trace_id: optional run ID propagated to all events.
        """
        key = idempotency_key or str(uuid4())
        authorized: List[tuple[GuardedResource, AuthorizationResult]] = []

        for resource in self._resources:
            qty = requested.get(resource.resource, 0.0)
            try:
                result = resource.client.authorize(
                    session_token=resource.session_token,
                    agent_id=agent_id,
                    action_type=action_type,
                    description=description,
                    requested_quantity=qty,
                    idempotency_key=f"{key}:{resource.resource}",
                    trace_id=trace_id,
                    **kwargs,
                )
            except Exception as exc:
                logger.error(
                    "CompositeGuard authorize error — resource=%s: %s",
                    resource.resource, exc,
                )
                self._void_all(authorized, reason="COMPOSITE_AUTHORIZE_ERROR")
                raise

            if not result.is_authorized:
                self._void_all(authorized, reason="COMPOSITE_PARTIAL_DENIAL")
                all_results = [r for _, r in authorized] + [result]
                resources_so_far = [r.resource for r, _ in authorized] + [resource.resource]
                return CompositeAuthorizationResult(
                    resources=resources_so_far,
                    authorizations=all_results,
                    all_authorized=False,
                    first_denial=result,
                    first_denial_resource=resource.resource,
                )

            authorized.append((resource, result))

        return CompositeAuthorizationResult(
            resources=[r.resource for r in self._resources],
            authorizations=[r for _, r in authorized],
            all_authorized=True,
        )

    def confirm(
        self,
        result: CompositeAuthorizationResult,
        confirmed: Dict[str, float],
    ) -> List[SpendEventResponse]:
        """
        Confirm all authorized resources with their actual consumed quantities.

        :param confirmed: mapping of resource name → confirmed quantity.
                          Unspecified resources are confirmed at 0.0.
        """
        events = []
        for resource, auth in zip(self._resources, result.authorizations):
            if not auth.is_authorized:
                continue
            qty = confirmed.get(resource.resource, 0.0)
            try:
                event = resource.client.confirm_event(auth.event_id, confirmed_quantity=qty)
                events.append(event)
            except Exception as exc:
                logger.warning(
                    "CompositeGuard confirm swallowed — resource=%s event=%s: %s",
                    resource.resource, auth.event_id, exc,
                )
        return events

    def fail(
        self,
        result: CompositeAuthorizationResult,
        reason: str = "TOOL_ERROR",
        error_message: Optional[str] = None,
    ) -> None:
        """Fail all authorized resources (action failed after authorization)."""
        for resource, auth in zip(self._resources, result.authorizations):
            if not auth.is_authorized:
                continue
            try:
                resource.client.fail_event(
                    auth.event_id, reason=reason, error_message=error_message
                )
            except Exception as exc:
                logger.warning(
                    "CompositeGuard fail_event error — resource=%s event=%s: %s",
                    resource.resource, auth.event_id, exc,
                )

    def _void_all(
        self,
        authorized: List[tuple[GuardedResource, AuthorizationResult]],
        reason: str,
    ) -> None:
        """Void all already-authorized events. Failures are logged, not raised."""
        for resource, auth in authorized:
            try:
                resource.client.void_event(auth.event_id, reason=reason)
            except Exception as exc:
                logger.warning(
                    "CompositeGuard void_event error — resource=%s event=%s: %s "
                    "(auto-expiry will clean up if authorizationExpirySeconds is set)",
                    resource.resource, auth.event_id, exc,
                )
